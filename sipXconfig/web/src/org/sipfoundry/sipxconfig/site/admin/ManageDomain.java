/*
 *
 *
 * Copyright (C) 2007 Pingtel Corp., certain elements licensed under a Contributor Agreement.
 * Contributors retain copyright to elements licensed under a Contributor Agreement.
 * Licensed to the User under the LGPL license.
 *
 * $
 */
package org.sipfoundry.sipxconfig.site.admin;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.apache.tapestry.annotations.InitialValue;
import org.apache.tapestry.annotations.InjectObject;
import org.apache.tapestry.annotations.Persist;
import org.apache.tapestry.event.PageBeginRenderListener;
import org.apache.tapestry.event.PageEvent;
import org.sipfoundry.sipxconfig.common.UserException;
import org.sipfoundry.sipxconfig.components.PageWithCallback;
import org.sipfoundry.sipxconfig.components.SipxValidationDelegate;
import org.sipfoundry.sipxconfig.components.TapestryContext;
import org.sipfoundry.sipxconfig.components.TapestryUtils;
import org.sipfoundry.sipxconfig.domain.Domain;
import org.sipfoundry.sipxconfig.domain.DomainManager;
import org.sipfoundry.sipxconfig.domain.DomainSettings;

/**
 * Edit single domain and it's aliases
 */
public abstract class ManageDomain extends PageWithCallback implements PageBeginRenderListener {
    public static final String PAGE = "admin/ManageDomain";

    @InjectObject (value = "spring:tapestry")
    public abstract TapestryContext getTapestry();

    @InjectObject (value = "spring:domainManager")
    public abstract DomainManager getDomainManager();

    @InitialValue (value = "ognl:domainManager.editableDomain")
    public abstract Domain getDomain();

    public abstract int getIndex();

    public abstract void setDomain(Domain domain);

    @Persist (value = "session")
    public abstract List<String> getAliases();

    public abstract void setAliases(List<String> aliases);

    public abstract DomainSettings getSettings();

    public abstract void setSettings(DomainSettings settings);

    public void pageBeginRender(PageEvent event) {
        if (getSettings() == null) {
            setSettings(getDomainManager().getSettings());
        }
        List<String> aliases = getAliases();
        if (aliases == null) {
            aliases = new ArrayList<String>();
            aliases.addAll(getDomain().getAliases());
            setAliases(aliases);
        }
    }

    public void removeAlias(int index) {
        getAliases().remove(index);
    }

    public void submit() {
        getAliases().add(StringUtils.EMPTY);
    }

    public void setAlias(String alias) {
        getAliases().set(getIndex(), alias);
    }

    public String getAlias() {
        return getAliases().get(getIndex());
    }

    public void commit() {
        if (!TapestryUtils.isValid(getPage())) {
            return;
        }

        DomainSettings settings = getSettings();
        getDomainManager().saveSettings(settings);

        String s = StringUtils.join(getAliases(), ", ");
        if (s.length() > settings.getAliasLength()) {
            SipxValidationDelegate validator = (SipxValidationDelegate) TapestryUtils.getValidator(this);
            validator.record(new UserException("&msg.aliasListTooLong", s.length()), getMessages());
            return;
        }

        Domain d = getDomain();
        d.getAliases().clear();
        d.getAliases().addAll(getAliases());
        getDomainManager().saveDomain(d);
    }
}
