package org.sipfoundry.sipxconfig.dialplan;

import org.sipfoundry.sipxconfig.dialplan.config.FullTransform;
import org.sipfoundry.sipxconfig.dialplan.config.Transform;
import org.sipfoundry.sipxconfig.dialplan.config.UrlTransform;

public class UnassignedDidRule extends DialingRule {
    
    private static final String USER_PART = "IVR";
    private static final String FQDN_PREFIX = "vm.";
    private final Transform m_transform;
    private final DialPattern m_dialPattern;
    
    public UnassignedDidRule(String prefix, int digits, String addrLocation, String redirectExtension) {
        setEnabled(true);
        m_dialPattern = new DialPattern(prefix, digits);
        if (redirectExtension == null) {
            m_transform = new UrlTransform();
            ((UrlTransform)m_transform).setUrl(
                MappingRule.buildUrl(USER_PART, FQDN_PREFIX + addrLocation, "dialed={digits};action=unassigneddid", null, null));
        } else {
            m_transform = new FullTransform();
            ((FullTransform)m_transform).setUser(redirectExtension);
        }
    }

    @Override
    public boolean isInternal() {
        return false;
    }

    @Override
    public boolean isGatewayAware() {
        return false;
    }

    @Override
    public String[] getPatterns() {
        return new String[] {
            m_dialPattern.calculatePattern()
        };        
    }

    @Override
    public Transform[] getTransforms() {
        return new Transform[] {
            m_transform
        };
    }

    @Override
    public DialingRuleType getType() {        
        return DialingRuleType.UNASSIGNED_DID;
    }
    
    @Override
    public String getDescription() {
        return "Unassigned DID";
    }
    
    @Override
    public CallTag getCallTag() {
        return CallTag.CUST;
    }    
}
