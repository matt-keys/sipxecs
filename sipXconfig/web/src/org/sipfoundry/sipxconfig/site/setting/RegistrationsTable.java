/*
 *
 *
 * Copyright (C) 2007 Pingtel Corp., certain elements licensed under a Contributor Agreement.
 * Contributors retain copyright to elements licensed under a Contributor Agreement.
 * Licensed to the User under the LGPL license.
 *
 * $
 */
package org.sipfoundry.sipxconfig.site.setting;

import java.text.DecimalFormat;
import java.util.Collection;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.DateUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.tapestry.BaseComponent;
import org.apache.tapestry.IMarkupWriter;
import org.apache.tapestry.IRequestCycle;
import org.apache.tapestry.annotations.Bean;
import org.apache.tapestry.annotations.ComponentClass;
import org.apache.tapestry.annotations.InjectObject;
import org.apache.tapestry.annotations.Parameter;
import org.apache.tapestry.bean.EvenOdd;
import org.sipfoundry.sipxconfig.commserver.imdb.RegistrationItem;
import org.sipfoundry.sipxconfig.registrar.RegistrationContext;

@ComponentClass(allowBody = false, allowInformalParameters = false)
public abstract class RegistrationsTable extends BaseComponent {
    public static final Log LOG = LogFactory.getLog(RegistrationsTable.class);

    @InjectObject(value = "spring:registrationContext")
    public abstract RegistrationContext getRegistrationContext();

    @Parameter(required = true)
    public abstract Collection getRegistrations();

    @Bean
    public abstract EvenOdd getRowClass();

    @Bean(initializer = "maximumFractionDigits=2,minimumFractionDigits=2")
    public abstract DecimalFormat getTwoDigitDecimal();

    public abstract RegistrationItem getCurrentRow();

    public abstract long getStartTime();

    public abstract void setStartTime(long startTime);

    protected void renderComponent(IMarkupWriter writer, IRequestCycle cycle) {
        long startRenderingTime = System.currentTimeMillis() / DateUtils.MILLIS_PER_SECOND;
        setStartTime(startRenderingTime);
        super.renderComponent(writer, cycle);
    }

    public String getColumnNames() {
        StringBuilder columnNames = new StringBuilder("uri,contact,expires,server,instrument");
        return columnNames.toString();
    }

    public Object getExpires() {
        RegistrationItem item = getCurrentRow();
        long timeToExpire = item.timeToExpireAsSeconds(getStartTime());
        if (timeToExpire > 0) {
            return timeToExpire;
        }
        return getMessages().getMessage("status.expired");
    }
    
    public Object getServer() {
        RegistrationItem item = getCurrentRow();
        String server = item.getPrimary();
        if (!StringUtils.isEmpty(server)) {
            return server;
        }
        return getMessages().getMessage("status.server");
    }
}
