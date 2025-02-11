/*
 *
 *
 * Copyright (C) 2008 Pingtel Corp., certain elements licensed under a Contributor Agreement.
 * Contributors retain copyright to elements licensed under a Contributor Agreement.
 * Licensed to the User under the LGPL license.
 *
 * $
 */
package org.sipfoundry.sipxconfig.cert;

import static java.lang.String.format;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.sipfoundry.sipxconfig.alarm.AlarmDefinition;
import org.sipfoundry.sipxconfig.alarm.AlarmProvider;
import org.sipfoundry.sipxconfig.alarm.AlarmServerManager;
import org.sipfoundry.sipxconfig.cfgmgt.ConfigManager;
import org.sipfoundry.sipxconfig.common.DaoUtils;
import org.sipfoundry.sipxconfig.common.UserException;
import org.sipfoundry.sipxconfig.commserver.Location;
import org.sipfoundry.sipxconfig.commserver.LocationsManager;
import org.sipfoundry.sipxconfig.domain.Domain;
import org.sipfoundry.sipxconfig.setting.BeanWithSettingsDao;
import org.sipfoundry.sipxconfig.setup.SetupListener;
import org.sipfoundry.sipxconfig.setup.SetupManager;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Certificate Management Implementation.
 */
public class CertificateManagerImpl implements CertificateManager, SetupListener, AlarmProvider {
    private static final Log LOG = LogFactory.getLog(CertificateManager.class);

    private static final String ALARM_CERTIFICATE_WILL_EXPIRE_STR = "ALARM_CERTIFICATE_WILL_EXPIRE Certificate: %s will expire on %s";
    private static final String ALARM_CERTIFICATE_DATE_RANGE_FUTURE_STR = "ALARM_CERTIFICATE_DATE_RANGE_FUTURE Certificate: %s valid date range starts on %s, it is not yet valid.";

    private static final String LOCK_FILE = "/var/sipxdata/cfdata/letsencrypt.lock";
    private static final String AUTHORITY_TABLE = "authority";
    private static final String CERT_TABLE = "cert";
    private static final String CERT_COLUMN = "data";
    private static final String KEY_COLUMN = "private_key";
    private static final String SELF_SIGN_AUTHORITY_PREFIX = "ca.";
    private BeanWithSettingsDao<CertificateSettings> m_settingsDao;
    private LocationsManager m_locationsManager;
    private JdbcTemplate m_jdbc;
    private ConfigManager m_configManager;
    private List<String> m_thirdPartyAuthorites;
    private String m_letsencryptConfigParams;
    private String m_letsencryptEmailChangeParams;

    @Override
    public CertificateSettings getSettings() {
        return m_settingsDao.findOrCreateOne();
    }

    @Override
    public void saveSettings(CertificateSettings settings) {
        m_settingsDao.upsert(settings);
    }

    @Override
    public void setWebCertificate(String cert) {
        setWebCertificate(cert, null);
    }

    @Override
    public void setWebCertificate(String cert, String key) {
        validateCert(cert, key);
        updateCertificate(WEB_CERT, cert, key, getSelfSigningAuthority());
    }

    @Override
    public String getNamedPrivateKey(String id) {
        return getSecurityData(CERT_TABLE, KEY_COLUMN, id);
    }

    @Override
    public String getNamedCertificate(String id) {
        return getSecurityData(CERT_TABLE, CERT_COLUMN, id);
    }

    @Override
    public void setCommunicationsCertificate(String cert) {
        setCommunicationsCertificate(cert, null);
    }

    @Override
    public void setCommunicationsCertificate(String cert, String key) {
        validateCert(cert, key);
        updateCertificate(COMM_CERT, cert, key, getSelfSigningAuthority());
    }

    @Override
    public String getChainCertificate() {
        return getSecurityData(CERT_TABLE, CERT_COLUMN, CHAIN_CERT);
    }

    @Override
    public void setChainCertificate(String cert) {
        validateCert(cert, null);
        updateCertificate(CHAIN_CERT, cert, null, null);
    }

    @Override
    public String getCACertificate() {
        return getSecurityData(CERT_TABLE, CERT_COLUMN, CA_CERT);
    }

    @Override
    public void setCACertificate(String cert) {
        validateCert(cert, null);
        updateCertificate(CA_CERT, cert, null, null);
    }

    private void updateCertificate(String name, String cert, String key, String authority) {
        updateNamedCertificate(name, cert, key, authority);
        m_configManager.configureEverywhere(FEATURE);
    }

    @Override
    public void updateNamedCertificate(String name, String cert, String key, String authority) {
        m_jdbc.update("delete from cert where name = ?", name);
        m_jdbc.update("insert into cert (name, data, private_key, authority) values (?, ?, ?, ?)", name, cert, key,
                authority);
    }

    @Override    
    public void checkAllCertificatesVlaidity() {
        LOG.debug("SCHEDULED JOB: Check certificates validity");
        List<Map<String, Object>> certs = getCertificates();
        for (Map<String, Object> cert : certs) {
            // don't check web certificate when using Let's Encrypt service
            if (getLetsEncryptStatus() && cert.get("name").toString().equals("ssl-web")) {
                continue;
            }

            X509Certificate certificate = CertificateUtils.readCertificate(cert.get("data").toString());
            try {
                //construct the date two weeks after current date
                int noOfDays = 14;
                Calendar calendar = Calendar.getInstance();
                calendar.setTime(new Date());            
                calendar.add(Calendar.DAY_OF_YEAR, noOfDays);
                Date date = calendar.getTime();
                certificate.checkValidity(date);
            } catch (CertificateExpiredException e) {
                LOG.error(String.format(ALARM_CERTIFICATE_WILL_EXPIRE_STR, cert.get("name"), certificate.getNotAfter()));
            } catch (CertificateNotYetValidException e) {
                LOG.error(String.format(ALARM_CERTIFICATE_DATE_RANGE_FUTURE_STR, cert.get("name"), certificate.getNotBefore()));                
            }
        }
    }

    @Override
    public Collection<AlarmDefinition> getAvailableAlarms(AlarmServerManager manager) {
        return Arrays.asList(new AlarmDefinition[]{ALARM_CERTIFICATE_WILL_EXPIRE, ALARM_CERTIFICATE_DATE_RANGE_FUTURE});
    }

    private void addThirdPartyAuthority(String name, String data) {
        addAuthority(name, data, null);
    }

    private void addAuthority(String name, String data, String key) {
        m_jdbc.update("delete from authority where name = ? ", name);
        m_jdbc.update("delete from cert where authority = ? ", name); // should be zero
        m_jdbc.update("insert into authority (name, data, private_key) values (?, ?, ?)", name, data, key);
        m_configManager.configureEverywhere(FEATURE);
    }

    private String getSecurityData(String table, String column, String name) {
        String sql = format("select %s from %s where name = ?", column, table);
        return DaoUtils.requireOneOrZero(m_jdbc.queryForList(sql, String.class, name), sql);
    }   

    @Override
    public String getWebCertificate() {
        return getSecurityData(CERT_TABLE, CERT_COLUMN, WEB_CERT);
    }

    @Override
    public String getWebPrivateKey() {
        return getSecurityData(CERT_TABLE, KEY_COLUMN, WEB_CERT);
    }

    @Override
    public String getCommunicationsCertificate() {
        return getSecurityData(CERT_TABLE, CERT_COLUMN, COMM_CERT);
    }

    @Override
    public String getCommunicationsPrivateKey() {
        return getSecurityData(CERT_TABLE, KEY_COLUMN, COMM_CERT);
    }

    public List<Map<String, Object>> getCertificates() {
        String sql = format("select name,data from %s", CERT_TABLE);
        List<Map<String, Object>> certificates = m_jdbc.queryForList(sql);
        return certificates;
    }

    @Override
    public List<String> getThirdPartyAuthorities() {
        List<String> authorities = m_jdbc.queryForList("select name from authority where name != ? order by name",
                String.class, getSelfSigningAuthority());
        return authorities;
    }

    @Override
    public List<String> getAuthorities() {
        List<String> authorities = m_jdbc.queryForList("select name from authority order by name", String.class);
        return authorities;
    }

    @Override
    public String getAuthorityCertificate(String authority) {
        return getSecurityData(AUTHORITY_TABLE, CERT_COLUMN, authority);
    }

    @Override
    public String getAuthorityKey(String authority) {
        return getSecurityData(AUTHORITY_TABLE, KEY_COLUMN, authority);
    }

    @Override
    public String getSelfSigningAuthority() {
        String domain = Domain.getDomain().getName();
        return SELF_SIGN_AUTHORITY_PREFIX + domain;
    }

    @Override
    public String getSelfSigningAuthorityText() {
        return getAuthorityCertificate(getSelfSigningAuthority());
    }

    @Override
    public void addTrustedAuthority(String authority, String cert) {
        validateAuthority(cert);
        addAuthority(authority, cert, null);
    }

    @Override
    public void rebuildSelfSignedData(int keySize) {
        forceDeleteTrustedAuthority(getSelfSigningAuthority());
        checkSetup(keySize);
    }

    @Override
    public void rebuildCommunicationsCert(int keySize) {
        rebuildCert(COMM_CERT, keySize);
    }

    @Override
    public void rebuildWebCert(int keySize) {
        rebuildCert(WEB_CERT, keySize);
    }

    private void rebuildCert(String type, int keySize) {
        String domain = Domain.getDomain().getName();
        String fqdn = m_locationsManager.getPrimaryLocation().getFqdn();
        String authority = getSelfSigningAuthority();
        String issuer = getIssuer(authority);
        String authKey = getAuthorityKey(authority);
        CertificateGenerator gen;
        List<String> altLocations = getAltLocations();
        if (type.equals(COMM_CERT)) {
            gen = CertificateGenerator.sip(domain, fqdn, issuer, authKey, altLocations);
        } else {
            gen = CertificateGenerator.web(domain, fqdn, issuer, authKey, altLocations);
        }
        gen.setBitCount(keySize);
        updateCertificate(type, gen.getCertificateText(), gen.getPrivateKeyText(), authority);
    }

    private List<String> getAltLocations() {
        Location[] locations = m_locationsManager.getLocations();
        List<String> altLocations = new ArrayList<String>();
        for (Location location : locations) {
            if (!location.isPrimary()) {
                altLocations.add(location.getFqdn());
            }
        }
        return altLocations;
    }

    @Override
    public void deleteTrustedAuthority(String authority) {
        if (authority.equals(getSelfSigningAuthority())) {
            throw new UserException("Cannot delete self signing certificate authority");
        }

        forceDeleteTrustedAuthority(authority);
    }

    private void forceDeleteTrustedAuthority(String authority) {
        m_jdbc.update("delete from authority where name = ?", authority);
        m_jdbc.update("delete from cert where authority = ?", authority);
        m_configManager.configureEverywhere(FEATURE);
    }

    private void checkSetup() {
        checkSetup(AbstractCertificateCommon.DEFAULT_KEY_SIZE);
    }

    public void checkSetup(int keySize) {
        String domain = Domain.getDomain().getName();
        String authority = getSelfSigningAuthority();
        String authorityCertificate = getAuthorityCertificate(authority);
        if (authorityCertificate == null) {
            CertificateAuthorityGenerator gen = new CertificateAuthorityGenerator(domain);
            addAuthority(authority, gen.getCertificateText(), gen.getPrivateKeyText());
        }
        for (String thirdPartAuth : m_thirdPartyAuthorites) {
            if (getAuthorityCertificate(thirdPartAuth) == null) {
                InputStream thirdPartIn = null;
                try {
                    thirdPartIn = getClass().getResourceAsStream(thirdPartAuth);
                    if (thirdPartIn == null) {
                        throw new IOException("Missing resource " + thirdPartAuth);
                    }
                    String thirdPartCert = IOUtils.toString(thirdPartIn);
                    String thirdPartAuthId = CertificateUtils.stripPath(thirdPartAuth);
                    addThirdPartyAuthority(thirdPartAuthId, thirdPartCert);
                } catch (IOException e) {
                    LOG.error("Cannot import authority " + thirdPartAuth, e);
                } finally {
                    IOUtils.closeQuietly(thirdPartIn);
                }
            }
        }

        String fqdn = m_locationsManager.getPrimaryLocation().getFqdn();
        String issuer = getIssuer(authority);
        String authKey = getAuthorityKey(authority);
        List<String> altLocations = getAltLocations();
        if (!hasCertificate(COMM_CERT, authority)) {
            CertificateGenerator gen = CertificateGenerator.sip(domain, fqdn, issuer, authKey, altLocations);
            gen.setBitCount(keySize);
            updateCertificate(COMM_CERT, gen.getCertificateText(), gen.getPrivateKeyText(), authority);
        }
        if (!hasCertificate(WEB_CERT, authority)) {
            CertificateGenerator gen = CertificateGenerator.web(domain, fqdn, issuer, authKey, altLocations);
            gen.setBitCount(keySize);
            updateCertificate(WEB_CERT, gen.getCertificateText(), gen.getPrivateKeyText(), authority);
        }
    }

    private boolean hasCertificate(String id, String authority) {
        int check = m_jdbc.queryForInt("select count(*) from cert where name = ? and authority = ?", id, authority);
        return (check >= 1);
    }

    private String getIssuer(String authority) {
        String authCertText = getSecurityData(AUTHORITY_TABLE, CERT_COLUMN, authority);
        X509Certificate authCert = CertificateUtils.readCertificate(authCertText);
        return authCert.getSubjectDN().getName();
    }

    private static void validateCert(String certTxt, String keyTxt) {
        X509Certificate cert = CertificateUtils.readCertificate(certTxt);
        try {
            cert.checkValidity();
        } catch (CertificateExpiredException e) {
            throw new UserException("Certificate has expired.");
        } catch (CertificateNotYetValidException e) {
            throw new UserException("Certificate valid date range is in the future, it is not yet valid.");
        }
        if (StringUtils.isNotBlank(keyTxt)) {
            CertificateUtils.readCertificateKey(keyTxt);
        }
        // to do, validate key w/cert and cert w/authorities
    }

    private static void validateAuthority(String cert) {
        validateCert(cert, null);
        // to do validate authority cert
    }

    @Override
    public boolean setup(SetupManager manager) {
        checkSetup();
        return true;
    }

    @Override
    public boolean getLetsEncryptStatus() {
        return getSettings().getUseLetsEncrypt();
    }

    @Override
    public boolean configureLetsEncryptService(CertificateSettings newSettings) {
        CertificateSettings oldSettings = getSettings();
        CommandExecutionStatus status = getCertbotCommandStatus();

        if (oldSettings.getLetsEncryptEmail() != null && oldSettings.getLetsEncryptEmail().equals(newSettings.getLetsEncryptEmail()) 
                && oldSettings.getLetsEncryptKeySize().equals(newSettings.getLetsEncryptKeySize())
                && (status.equals(CommandExecutionStatus.IN_PROGRESS) || status.equals(CommandExecutionStatus.SUCCESS))) {
            // nothing was changed or execution in progress
            return false;
        }

        String fqdn = m_locationsManager.getPrimaryLocation().getFqdn();
        String params;

        if (getLetsEncryptStatus() && oldSettings.getLetsEncryptKeySize().equals(newSettings.getLetsEncryptKeySize())
                && status.equals(CommandExecutionStatus.SUCCESS)) {
            // just update the email address (if previous run was successfull)
            params = String.format(m_letsencryptEmailChangeParams, newSettings.getLetsEncryptEmail());
        } else {
            // (re)configure certbot
            params = String.format(m_letsencryptConfigParams, fqdn, newSettings.getLetsEncryptKeySize(),
                    newSettings.getLetsEncryptEmail());
        }

        newSettings.setSettingTypedValue("letsencrypt/useLetsEncrypt", true);
        newSettings.setSettingTypedValue("letsencrypt/certbotParams", params);
        saveSettings(newSettings);

        File lockFile = new File(LOCK_FILE);
        if (lockFile.exists()) {
            // delete lockfile so sipxagent can execute certbot
            lockFile.delete();
        }

        m_configManager.configureEverywhere(FEATURE);

        return true;
    }

    @Override
    public void disableLetsEncryptService() {
        CertificateSettings settings = getSettings();
        settings.setSettingTypedValue("letsencrypt/useLetsEncrypt", false);
        settings.setSettingTypedValue("letsencrypt/letsEncryptEmail", "");
        saveSettings(settings);
        rebuildWebCert(settings.getLetsEncryptKeySize());
    }

    @Override
    public CommandExecutionStatus getCertbotCommandStatus() {
        File lockFile = new File(LOCK_FILE);

        if (!lockFile.exists()) {
            return CommandExecutionStatus.IN_PROGRESS;
        }

        String line = "";
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(lockFile));
            line = br.readLine();

            int val = Integer.valueOf(line);
            if (val == 0) {
                return CommandExecutionStatus.SUCCESS;
            } else {
                return CommandExecutionStatus.FAIL;
            }
        } catch (Exception e) {
            LOG.error(e);
            return CommandExecutionStatus.ERROR;
        } finally {
            IOUtils.closeQuietly(br);
        }
    }

    public void setJdbc(JdbcTemplate jdbc) {
        m_jdbc = jdbc;
    }

    public void setLocationsManager(LocationsManager locationsManager) {
        m_locationsManager = locationsManager;
    }

    public void setSettingsDao(BeanWithSettingsDao<CertificateSettings> settingsDao) {
        m_settingsDao = settingsDao;
    }

    public void setConfigManager(ConfigManager configManager) {
        m_configManager = configManager;
    }

    public void setThirdPartyAuthorites(List<String> thirdPartyAuthorites) {
        m_thirdPartyAuthorites = thirdPartyAuthorites;
    }

    public void setLetsencryptConfigParams(String letsencryptConfigParams) {
        m_letsencryptConfigParams = letsencryptConfigParams;
    }

    public void setLetsencryptEmailChangeParams(String letsencryptEmailChangeParams) {
        m_letsencryptEmailChangeParams = letsencryptEmailChangeParams;
    }
}
