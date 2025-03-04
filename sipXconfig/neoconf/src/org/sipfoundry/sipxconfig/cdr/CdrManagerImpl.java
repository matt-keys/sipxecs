/*
 *
 *
 * Copyright (C) 2007 Pingtel Corp., certain elements licensed under a Contributor Agreement.
 * Contributors retain copyright to elements licensed under a Contributor Agreement.
 * Licensed to the User under the LGPL license.
 *
 * $
 */
package org.sipfoundry.sipxconfig.cdr;

import java.io.IOException;
import java.io.Writer;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.Format;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import javax.xml.rpc.ServiceException;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.DateFormatUtils;
import org.apache.commons.lang.time.DateUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.sipfoundry.commons.util.TimeZoneUtils;
import org.sipfoundry.sipxconfig.address.Address;
import org.sipfoundry.sipxconfig.address.AddressManager;
import org.sipfoundry.sipxconfig.address.AddressProvider;
import org.sipfoundry.sipxconfig.address.AddressType;
import org.sipfoundry.sipxconfig.api.impl.BaseCdrApiImpl;
import org.sipfoundry.sipxconfig.backup.ArchiveDefinition;
import org.sipfoundry.sipxconfig.backup.ArchiveProvider;
import org.sipfoundry.sipxconfig.backup.BackupManager;
import org.sipfoundry.sipxconfig.backup.BackupPlan;
import org.sipfoundry.sipxconfig.backup.BackupSettings;
import org.sipfoundry.sipxconfig.cdr.Cdr.Termination;
import org.sipfoundry.sipxconfig.common.User;
import org.sipfoundry.sipxconfig.common.UserException;
import org.sipfoundry.sipxconfig.commserver.Location;
import org.sipfoundry.sipxconfig.feature.Bundle;
import org.sipfoundry.sipxconfig.feature.BundleConstraint;
import org.sipfoundry.sipxconfig.feature.FeatureChangeRequest;
import org.sipfoundry.sipxconfig.feature.FeatureChangeValidator;
import org.sipfoundry.sipxconfig.feature.FeatureManager;
import org.sipfoundry.sipxconfig.feature.FeatureProvider;
import org.sipfoundry.sipxconfig.feature.GlobalFeature;
import org.sipfoundry.sipxconfig.feature.LocationFeature;
import org.sipfoundry.sipxconfig.firewall.DefaultFirewallRule;
import org.sipfoundry.sipxconfig.firewall.FirewallManager;
import org.sipfoundry.sipxconfig.firewall.FirewallProvider;
import org.sipfoundry.sipxconfig.proxy.ProxyManager;
import org.sipfoundry.sipxconfig.setting.BeanWithSettingsDao;
import org.sipfoundry.sipxconfig.snmp.ProcessDefinition;
import org.sipfoundry.sipxconfig.snmp.ProcessProvider;
import org.sipfoundry.sipxconfig.snmp.SnmpManager;
import org.sipfoundry.sipxconfig.time.NtpManager;
import org.springframework.beans.factory.annotation.Required;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SingleColumnRowMapper;
import org.springframework.jdbc.core.support.JdbcDaoSupport;

import com.thoughtworks.xstream.XStream;

public class CdrManagerImpl extends JdbcDaoSupport implements CdrManager, FeatureProvider, AddressProvider,
        ProcessProvider, FirewallProvider, ArchiveProvider {
    static final String CALL_ID = "call_id";
    static final String CALLEE_AOR = "callee_aor";
    static final String TERMINATION = "termination";
    static final String FAILURE_STATUS = "failure_status";
    static final String END_TIME = "end_time";
    static final String CONNECT_TIME = "connect_time";
    static final String START_TIME = "start_time";
    static final String CALLER_AOR = "caller_aor";
    static final String CALL_REFERENCE = "reference";
    static final String CALLER_INTERNAL = "caller_internal";
    static final String CALLEE_ROUTE = "callee_route";
    static final String CALLEE_CONTACT = "callee_contact";
    static final String CALLER_CONTACT = "caller_contact";
    static final String CALLED_NUMBER = "called_number";
    static final String GATEWAY = "gateway";

    /**
     * CDRs database at the moment is using 'timestamp' type to store UTC time. Postgres
     * 'timestamp' does not store any time zone information and JDBC driver for postgres would
     * interpret as local time. We pass TimeZone explicitely to force interpreting zoneless
     * timestamp as UTC timestamps.
     */
    private final TimeZone m_tz = DateUtils.UTC_TIME_ZONE;
    private AddressManager m_addressManager;
    private FeatureManager m_featureManager;
    private BeanWithSettingsDao<CdrSettings> m_settingsDao;
    private NtpManager m_ntpManager;
    private static final Log LOG = LogFactory.getLog(CdrManagerImpl.class);

    @Override
    public CdrSettings getSettings() {
        return m_settingsDao.findOrCreateOne();
    }

    @Override
    public void saveSettings(CdrSettings settings) {
        m_settingsDao.upsert(settings);
    }

    public List<Cdr> getCdrs(Date from, Date to, User user) {
        return getCdrs(from, to, new CdrSearch(), user);
    }

    @Override
    public List<Cdr> getCdrs(Date from, Date to, CdrSearch search, User user) {
        return getCdrs(from, to, search, user, 0, 0, false);
    }

    @Override
    public List<Cdr> getCdrs(Date from, Date to, CdrSearch search, User user, int limit, int offset, boolean recipient) {
        return getCdrs(from, to, search, user, null, limit, offset, recipient);
    }

    @Override
    public List<Cdr> getCdrs(Date fromDate, Date toDate, CdrSearch search, User user,
        TimeZone timeZone, int limit, int offset, boolean recipient) {
    	LOG.debug("RECIPIENT: " + recipient);
        Date from = fromDate;
        Date to = toDate;
        if (timeZone != null) {
            from = TimeZoneUtils.getSameDateWithNewTimezone(fromDate, timeZone);
            to = TimeZoneUtils.getSameDateWithNewTimezone(toDate, timeZone);
        }
        CdrsStatementCreator psc = new SelectAll(from, to, search, user, (user != null)
            ? (user.getTimezone()) : m_tz, limit, offset, recipient);
        TimeZone resultsTimeZone = timeZone;
        if (resultsTimeZone == null) {
            resultsTimeZone = (user != null) ? (user.getTimezone()) : (getTimeZone());
        }
        CdrsResultReader resultReader = new CdrsResultReader(resultsTimeZone,
                getSettings().getPrivacyStatus(), getSettings().getPrivacyMinLength(),
                getSettings().getPrivacyExcludeList());
        getJdbcTemplate().query(psc, resultReader);
        return resultReader.getResults();
    }
    
    @Override
    public Cse getCse(String extension) {
    	CseStatementCreator csc = new CseStatementCreator(extension);
    	CseResultReader resultReader = new CseResultReader();
    	getJdbcTemplate().query(csc, resultReader);
    	List<Cse> cses = resultReader.getResults();
    	return cses.isEmpty() ? null : cses.get(0);
    }

    private TimeZone getTimeZone() {
        String systemTimezone = m_ntpManager.getSystemTimezone();
        return systemTimezone == null ? TimeZone.getDefault() : TimeZone.getTimeZone(systemTimezone);
    }

    @Override
    public void dumpCdrs(Writer writer, Date from, Date to, TimeZone displayTimeZone,
        CdrSearch search, User user) throws IOException {
        ColumnInfoFactory columnInforFactory = new DefaultColumnInfoFactory(m_tz, displayTimeZone);
        CdrsWriter resultReader = new CdrsCsvWriter(writer, columnInforFactory);
        dump(resultReader, from, to, displayTimeZone, search, user, getSettings().getCsvLimit());
    }

    @Override
    public void dumpCdrsJson(Writer out) throws IOException {
        DefaultColumnInfoFactory columnInforFactory = new DefaultColumnInfoFactory(m_tz);
        columnInforFactory.setAorFormat(CdrsJsonWriter.AOR_FORMAT);
        CdrsWriter resultReader = new CdrsJsonWriter(out, columnInforFactory);
        // if we cannot see all the result - get only the latest
        CdrSearch cdrSearch = new CdrSearch();
        cdrSearch.setOrder(START_TIME, false);
        dump(resultReader, null, null, null, cdrSearch, null, getSettings().getJsonLimit());
    }

    /**
     * Current implementation only dumps at most m_csvLimit CDRs. This limitation is necessary due
     * to limitations of URLConnection used to download exported data to the client system.
     *
     * See: http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=4212479
     * http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=5026745
     *
     * If we had direct access to that connection we could try calling "setChunkedStreamingMode"
     * on it.
     */
    private void dump(CdrsWriter resultReader, Date fromDate, Date toDate, TimeZone timezone,
        CdrSearch search, User user, int limit)
        throws IOException {
        Date from = fromDate;
        Date to = toDate;
        if (timezone != null) {
            from = TimeZoneUtils.getSameDateWithNewTimezone(fromDate, timezone);
            to = TimeZoneUtils.getSameDateWithNewTimezone(toDate, timezone);
        }

        int count = (limit == 0 ? getCdrCount(from, to, search, user, false) : limit);
        count = (count > MAX_COUNT) ? MAX_COUNT : count;

        try {
            resultReader.writeHeader();

            int offset = 0;
            int pages = count / DUMP_PAGE;
            int remaining = count - pages * DUMP_PAGE;

            for (int i = 0; i < pages; i++) {
                PreparedStatementCreator psc = new SelectAll(from, to, search, user, (user != null)
                    ? (user.getTimezone()) : m_tz, DUMP_PAGE, offset, false);
                getJdbcTemplate().query(psc, resultReader);
                offset += DUMP_PAGE;
            }
            if (remaining > 0) {
                PreparedStatementCreator psc = new SelectAll(from, to, search, user, (user != null)
                    ? (user.getTimezone()) : m_tz, remaining, offset, false);
                getJdbcTemplate().query(psc, resultReader);
            }

            resultReader.writeFooter();
        } catch (DataAccessException e) {
            // unwrap IOException that might happen during reading DB
            if (e.getCause() instanceof IOException) {
                throw (IOException) e.getCause();
            }
            throw e;
        }
    }

    @Override
    public int getCdrCount(Date from, Date to, CdrSearch search, User user, boolean recipient) {
        CdrsStatementCreator psc = new SelectCount(from, to, search, user, m_tz, recipient);
        RowMapper rowMapper = new SingleColumnRowMapper(Integer.class);
        List results = getJdbcTemplate().query(psc, rowMapper);
        return (Integer) DataAccessUtils.requiredUniqueResult(results);
    }
    
    @Override
    public int deleteAll(Date from, Date to, CdrSearch search, User user, boolean recipient) {
        CdrsStatementCreator psc = new DeleteAll(from, to, search, user, m_tz, recipient);
        return getJdbcTemplate().update(psc);
    }

    @Override
    public List<Cdr> getActiveCalls() {
        try {
            // Now we use REST calls for this too
            return getActiveCallsRESTCall(null);
        } catch (IOException e) {
            throw new UserException(e);
        }
    }

    @Override
    public List<Cdr> getActiveCallsREST(User user) throws IOException {
        // We need this for security... if user is null in this case
        // we do not want to show any informations
        if (null == user) {
            return new ArrayList<Cdr>();
        } else {
            return getActiveCallsRESTCall(user);
        }
    }

    private List<Cdr> getActiveCallsRESTCall(User user) throws IOException {
        HttpClient client = new HttpClient();
        GetMethod getMethod = new GetMethod(getActiveCdrsRestUrl(user));
        int statusCode = HttpStatus.SC_OK;
        ArrayList<Cdr> cdrs = new ArrayList<Cdr>();
        Calendar c = Calendar.getInstance();
        statusCode = client.executeMethod(getMethod);
        if (statusCode == HttpStatus.SC_OK) {
            String xml = getMethod.getResponseBodyAsString();
            List<ActiveCallREST> list = mapActiveCalls(xml);
            for (ActiveCallREST call : list) {
                ActiveCallCdr cdr = new ActiveCallCdr();
                cdr.setCallerAor(call.getFrom());
                cdr.setCalleeAor(call.getTo());
                cdr.setCalleeContact(call.getRecipient());
                c.setTimeInMillis(call.getStartTime());
                cdr.setStartTime(c.getTime());
                cdr.setDuration(call.getDuration());
                cdrs.add(cdr);
            }
        }
        return cdrs;
    }

    private List<ActiveCallREST> mapActiveCalls(String xml) {
        XStream xstream = new XStream();
        xstream.alias("cdrs", List.class);
        xstream.alias("cdr", ActiveCallREST.class);
        xstream.aliasField("from", ActiveCallREST.class, "m_from");
        xstream.aliasField("to", ActiveCallREST.class, "m_to");
        xstream.aliasField("recipient", ActiveCallREST.class, "m_recipient");
        xstream.aliasField(START_TIME, ActiveCallREST.class, "m_startTime");
        xstream.aliasField("duration", ActiveCallREST.class, "m_duration");
        List<ActiveCallREST> cdrs = (List<ActiveCallREST>) xstream.fromXML(xml);
        return cdrs;
    }

    private Address getCdrAgentAddress() {
        return m_addressManager.getSingleAddress(CDR_API);
    }

    private String getActiveCdrsRestUrl(User user) {
        Address address = getCdrAgentAddress();
        if (null == user) {
            return String.format("http://%s:%d/activecdrs", address.getAddress(), address.getPort());
        } else {
            return String.format("http://%s:%d/activecdrs?name=%s", address.getAddress(), address.getPort(),
                user.getUserName());
        }
    }

    public CdrService getCdrService() {
        try {
            Address address = getCdrAgentAddress();
            URL url = new URL("http", address.getAddress(), address.getPort(), StringUtils.EMPTY);
            return new CdrImplServiceLocator().getCdrService(url);
        } catch (ServiceException e) {
            throw new UserException(e);
        } catch (MalformedURLException e) {
            throw new UserException(e);
        }
    }
    
    static class CseStatementCreator implements PreparedStatementCreator {
    	String m_extension;
    	
    	public CseStatementCreator(String extension) {
    		m_extension = extension;
    	}
    	
        @Override
        public PreparedStatement createPreparedStatement(Connection con) throws SQLException {
            StringBuilder sql = new StringBuilder("SELECT from_url, to_url FROM call_state_events WHERE to_url LIKE '%<sip:");
            sql.append(m_extension);
            sql.append("@%' and event_type='S' order by event_time desc limit 1");
            LOG.debug("CSE sql " + sql.toString());
            PreparedStatement ps = con.prepareStatement(sql.toString());
            return ps;
        }    	
    }

    abstract static class CdrsStatementCreator implements PreparedStatementCreator {
        private static final String FROM = " FROM cdrs WHERE (? <= start_time) AND (start_time <= ?)";
        private static final String LIMIT = " LIMIT ? ";
        private static final String OFFSET = " OFFSET ?";

        private final Timestamp m_from;
        private final Timestamp m_to;
        private final CdrSearch m_search;
        private CdrSearch m_forUser;
        private final int m_limit;
        private final int m_offset;
        private final Calendar m_calendar;

        public CdrsStatementCreator(Date from, Date to, CdrSearch search, User user, TimeZone tz, boolean recipient) {
            this(from, to, search, user, tz, 0, 0, recipient);
        }

        public CdrsStatementCreator(Date from, Date to, CdrSearch search, User user, TimeZone tz, int limit,
                int offset, boolean recipient) {
            m_calendar = Calendar.getInstance(tz);
            long fromMillis = from != null ? from.getTime() : 0;
            m_from = new Timestamp(fromMillis);
            long toMillis = to != null ? to.getTime() : System.currentTimeMillis();
            m_to = new Timestamp(toMillis);
            m_search = search;
            m_limit = limit;
            m_offset = offset;
            if (user != null) {
                m_forUser = new CdrSearch();
                if (!recipient) {
                	m_forUser.setMode(CdrSearch.Mode.ANY);
                } else {
                	m_forUser.setMode(CdrSearch.Mode.ANY_RECIPIENT);
                }
                Set<String> names = user.getAliases();
                names.add(user.getName());
                m_forUser.setTerm(names.toArray(new String[0]));
            }
        }

        @Override
        public PreparedStatement createPreparedStatement(Connection con) throws SQLException {
            StringBuilder sql = new StringBuilder(getSelectSql());
            sql.append(FROM);
            
            m_search.appendGetSql(sql);
            if (m_forUser != null) {
                m_forUser.appendGetSql(sql);
            }
            if (!StringUtils.startsWith(sql.toString(), "DELETE")) {
            	appendOrderBySql(sql);
            }
            if (m_limit > 0) {
                sql.append(LIMIT);
            }
            if (m_offset > 0) {
                sql.append(OFFSET);
            }
            LOG.debug("CDR sql " + sql.toString());
            PreparedStatement ps = con.prepareStatement(sql.toString());
            ps.setTimestamp(1, m_from, m_calendar);
            ps.setTimestamp(2, m_to, m_calendar);
            if (m_offset > 0 && m_limit > 0) {
                ps.setInt(3, m_limit);
                ps.setInt(4, m_offset);
            } 
            else if (m_limit > 0) {
                ps.setInt(3, m_limit);
            }
            else if (m_offset > 0) {
                ps.setInt(3, m_offset);
            }
            return ps;
        }

        public abstract String getSelectSql();

        protected void appendOrderBySql(StringBuilder sql) {
            m_search.appendOrderBySql(sql);
        }
    }

    static class SelectAll extends CdrsStatementCreator {
        public SelectAll(Date from, Date to, CdrSearch search, User user, TimeZone tz, int limit, int offset, boolean recipient) {
            super(from, to, search, user, tz, limit, offset, recipient);
        }

        public SelectAll(Date from, Date to, CdrSearch search, User user, TimeZone tz) {
            super(from, to, search, user, tz, false);
        }

        @Override
        public String getSelectSql() {
            return "SELECT *";
        }
    }
    
    static class DeleteAll extends CdrsStatementCreator {
        public DeleteAll(Date from, Date to, CdrSearch search, User user, TimeZone tz, boolean recipient) {
            super(from, to, search, user, tz, recipient);
        }

        public DeleteAll(Date from, Date to, CdrSearch search, User user, TimeZone tz) {
            super(from, to, search, user, tz, false);
        }

        @Override
        public String getSelectSql() {
            return "DELETE";
        }
    }
    
    static class SelectCount extends CdrsStatementCreator {

        public SelectCount(Date from, Date to, CdrSearch search, User user, TimeZone tz, boolean recipient) {
            super(from, to, search, user, tz, recipient);
        }

        @Override
        public String getSelectSql() {
            return "SELECT COUNT(id)";
        }

        @Override
        protected void appendOrderBySql(StringBuilder sql) {
            // no ordering when selecting COUNT
        }
    }
    
    static class CseResultReader implements RowCallbackHandler {
        List<Cse> m_cses = new ArrayList<Cse>();
    	
		@Override
		public void processRow(ResultSet rs) throws SQLException {
			Cse cse = new Cse(rs.getString("from_url"), rs.getString("to_url"));
			m_cses.add(cse);
		}

        public List<Cse> getResults() {
            return m_cses;
        }
    }

    /**
     * Spring 2.0.4 introduced ResultSetExtractor interface, may be more of a fit for what this
     * class is trying to acheive
     */
    static class CdrsResultReader implements RowCallbackHandler {
        private final List<Cdr> m_cdrs = new ArrayList<Cdr>();

        private final Calendar m_calendar;
        private final Calendar m_calendarGMT = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        private TimeZone m_systemTimeZone;

        private boolean m_privacy;
        private int m_privacyLimit;
        private String m_privacyExcluded;
        private String m_mask = StringUtils.EMPTY;
        private DateTimeZone m_dateTimeZone;

        public CdrsResultReader(TimeZone tz, boolean privacy, int limit, String excluded) {
            m_calendar = Calendar.getInstance(tz);
            m_privacy = privacy;
            m_privacyLimit = limit;
            m_privacyExcluded = excluded;
            m_dateTimeZone = DateTimeZone.forTimeZone(m_calendar.getTimeZone());
            for (int i = 0; i < m_privacyLimit; i++) {
                m_mask += "*";
            }
        }

        public CdrsResultReader(TimeZone tz) {
            m_calendar = Calendar.getInstance(tz);
        }

        public List<Cdr> getResults() {
            return m_cdrs;
        }

        @Override
        public void processRow(ResultSet rs) throws SQLException {
            Cdr cdr = new Cdr();
            cdr.setPrivacy(m_privacy);
            cdr.setPrivacyExcluded(m_privacyExcluded);
            cdr.setLimit(m_privacyLimit);
            cdr.setMask(m_mask);

            cdr.setCalleeAor(rs.getString(CALLEE_AOR));
            cdr.setCallerAor(rs.getString(CALLER_AOR));
            cdr.setCallId(rs.getString(CALL_ID));
            cdr.setReference(rs.getString(CALL_REFERENCE));
            cdr.setCallerInternal(rs.getBoolean(CALLER_INTERNAL));
            cdr.setCalleeRoute(rs.getString(CALLEE_ROUTE));
            cdr.setCalleeContact(rs.getString(CALLEE_CONTACT));
            cdr.setCallerContact(rs.getString(CALLER_CONTACT));
            Date startTime = rs.getTimestamp(START_TIME, m_calendarGMT);

            cdr.setStartTime((new DateTime(startTime).withZone(m_dateTimeZone).toLocalDateTime().toDate()));
            Date connectTime = rs.getTimestamp(CONNECT_TIME, m_calendar);
            cdr.setConnectTime(connectTime);
            cdr.setEndTime(rs.getTimestamp(END_TIME, m_calendar));
            cdr.setFailureStatus(rs.getInt(FAILURE_STATUS));
            String termination = rs.getString(TERMINATION);
            cdr.setTermination(Termination.fromString(termination));
            cdr.setCalledNumber(rs.getString(CALLED_NUMBER));
            cdr.setGateway(rs.getInt(GATEWAY));
            m_cdrs.add(cdr);
        }
    }

    public static Date convertJodaTimezone(DateTime date, String srcTz, String destTz) {
        DateTime srcDateTime = date.toDateTime(DateTimeZone.forID(srcTz));
        DateTime dstDateTime = srcDateTime.withZone(DateTimeZone.forID(destTz));
        return dstDateTime.toLocalDateTime().toDateTime().toDate();
    }

    static class ColumnInfo {
        /** List of fields that will be exported to CDR */
        static final String[] FIELDS = {
            CALLEE_AOR, CALLER_AOR, START_TIME, CONNECT_TIME, END_TIME, FAILURE_STATUS, TERMINATION, CALL_ID
        };
        static final boolean[] TIME_FIELDS = {
            false, false, true, true, true, false, false, false
        };
        static final boolean[] AOR_FIELDS = {
            true, true, false, false, false, false, false, false
        };

        private final int m_rsIndex;
        private final int m_fieldIndex;
        private final boolean m_timestamp;
        private Format m_format;
        private final Calendar m_calendar;
        private final TimeZone m_displayTimeZone;

        public ColumnInfo(ResultSet rs, int i, Calendar calendar, TimeZone displayTimeZone,
            Format dateFormat, Format aorFormat)
            throws SQLException {
            m_fieldIndex = i;
            m_calendar = calendar;
            m_displayTimeZone = displayTimeZone;
            m_rsIndex = rs.findColumn(FIELDS[m_fieldIndex]);
            m_timestamp = TIME_FIELDS[m_fieldIndex];
            if (AOR_FIELDS[m_fieldIndex]) {
                m_format = aorFormat;
            } else if (TIME_FIELDS[m_fieldIndex]) {
                m_format = dateFormat;
            }
        }

        int getIndex() {
            return m_rsIndex;
        }

        boolean isTimestamp() {
            return m_timestamp;
        }

        public Object get(ResultSet rs) throws SQLException {
            if (!m_timestamp) {
                return rs.getString(m_rsIndex);
            }
            return rs.getTimestamp(m_rsIndex, m_calendar);
        }

        public String formatValue(ResultSet rs) throws SQLException {
            Object v = get(rs);
            if (v == null) {
                return StringUtils.EMPTY;
            }
            if (m_format == null) {
                return v.toString();
            }
            if (m_timestamp) {
                Timestamp timestamp = (Timestamp) v;
                Date cal = TimeZoneUtils.convertDateToNewTimezone(new Date(
                        timestamp.getTime()), m_displayTimeZone);
                return m_format.format(cal);
            }
            return StringUtils.EMPTY;
        }

        public String getField() {
            return FIELDS[m_fieldIndex];
        }
    }

    interface ColumnInfoFactory {
        ColumnInfo[] create(ResultSet rs) throws SQLException;
    }

    static class DefaultColumnInfoFactory implements ColumnInfoFactory {
        private Format m_dateFormat = DateFormatUtils.ISO_DATETIME_TIME_ZONE_FORMAT;
        private Format m_aorFormat;
        private final Calendar m_calendar;
        private final TimeZone m_displayTimezone;

        public DefaultColumnInfoFactory(TimeZone tz) {
            this(tz, null);
        }

        public DefaultColumnInfoFactory(TimeZone tz, TimeZone displayTimeZone) {
            m_calendar = Calendar.getInstance(tz);
            m_displayTimezone = displayTimeZone;
        }

        @Override
        public ColumnInfo[] create(ResultSet rs) throws SQLException {
            ColumnInfo[] fields = new ColumnInfo[ColumnInfo.FIELDS.length];
            for (int i = 0; i < fields.length; i++) {
                ColumnInfo ci = new ColumnInfo(rs, i, m_calendar, m_displayTimezone, m_dateFormat, m_aorFormat);
                fields[i] = ci;
            }
            return fields;
        }

        public void setAorFormat(Format aorFormat) {
            m_aorFormat = aorFormat;
        }

        public void setDateFormat(Format dateFormat) {
            m_dateFormat = dateFormat;
        }
    }

    @Override
    public Collection<GlobalFeature> getAvailableGlobalFeatures(FeatureManager featureManager) {
        return null;
    }

    @Override
    public Collection<LocationFeature> getAvailableLocationFeatures(FeatureManager featureManager, Location l) {
        return Collections.singleton(FEATURE);
    }

    @Override
    public Collection<Address> getAvailableAddresses(AddressManager manager, AddressType type, Location requester) {
        if (!type.equals(CDR_API)) {
            return null;
        }
        List<Location> locations = m_featureManager.getLocationsForEnabledFeature(FEATURE);
        if (locations.isEmpty()) {
            return null;
        }

        CdrSettings settings = getSettings();
        return Collections.singleton(new Address(CDR_API, locations.get(0).getAddress(), settings.getAgentPort()));
    }

    public void setAddressManager(AddressManager addressManager) {
        m_addressManager = addressManager;
    }

    public void setSettingsDao(BeanWithSettingsDao<CdrSettings> settingsDao) {
        m_settingsDao = settingsDao;
    }

    public void setFeatureManager(FeatureManager featureManager) {
        m_featureManager = featureManager;
    }

    @Override
    public Collection<ProcessDefinition> getProcessDefinitions(SnmpManager manager, Location location) {
        boolean enabled = manager.getFeatureManager().isFeatureEnabled(FEATURE, location);
        return (enabled ? Collections.singleton(ProcessDefinition.sipxByRegex("sipxcdr",
                ".*\\s$(sipx.SIPX_LIBDIR)/ruby/gems/[0-9.]+/gems/sipxcallresolver-[0-9.]+/lib/main.rb\\s.*")) : null);
    }

    @Override
    public void getBundleFeatures(FeatureManager featureManager, Bundle b) {
        if (b == Bundle.CORE_TELEPHONY) {
            b.addFeature(FEATURE, BundleConstraint.PRIMARY_ONLY);
        }
    }

    @Override
    public Collection<DefaultFirewallRule> getFirewallRules(FirewallManager manager) {
        return Collections.singleton(new DefaultFirewallRule(CDR_API));
    }

    @Override
    public void featureChangePrecommit(FeatureManager manager, FeatureChangeValidator validator) {
        validator.requiresAtLeastOne(FEATURE, ProxyManager.FEATURE);
        validator.primaryLocationOnly(FEATURE);
    }

    @Override
    public void featureChangePostcommit(FeatureManager manager, FeatureChangeRequest request) {
    }

    @Override
    public Collection<ArchiveDefinition> getArchiveDefinitions(BackupManager manager, Location location,
            BackupPlan plan, BackupSettings manualSettings) {
        if (!manager.getFeatureManager().isFeatureEnabled(FEATURE, location)) {
            return null;
        }

        String tmpDir = "";
        if (manualSettings != null) {
            String tmpDirectory = manualSettings.getTmpDir();
            if (StringUtils.isNotBlank(tmpDirectory)) {
                tmpDir = " --tmp-dir " + tmpDirectory;
            }
        }
        ArchiveDefinition def = new ArchiveDefinition(ARCHIVE, "sipxcdr-archive --backup %s" + tmpDir,
                "sipxcdr-archive --restore %s" + tmpDir);
        return Collections.singleton(def);
    }

    @Required
    public void setNtpManager(NtpManager ntpManager) {
        m_ntpManager = ntpManager;
    }

}
