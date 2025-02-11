package org.sipfoundry.commons.diddb;

import java.util.List;

import org.springframework.beans.factory.annotation.Required;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

public class DidServiceImpl implements DidService {
    private MongoTemplate m_profiles;

    @Override
    public Did getDid(String typeId) {
        Did did = m_profiles.findOne(
            new Query(Criteria.where("typeId").is(typeId)), Did.class);
        return did;
    }
    
    public Did getActiveNextDid() {
        Did did = m_profiles.findOne(new Query(Criteria.where("_class").is("org.sipfoundry.commons.diddb.ActiveNextDid")),
             ActiveNextDid.class);
        return did;
    }

    @Override
    public void saveDid(Did did) {
        m_profiles.save(did);
    }
    
    @Override
    public void insertDids(List<Did> dids) {
      m_profiles.insertAll(dids);
    }
    
    @Override
    public void removeAllDids() {
        m_profiles.remove(
            new Query(Criteria.where("_class").is("org.sipfoundry.commons.diddb.Did")), Did.class);
    }
    
    @Override
    public void removeDid(String typeId) {
        Did did = getDid(typeId);
        if (did != null) {
            m_profiles.remove(did);
        }
    }

    @Override
    public List<Did> getAllDids() {        
        return m_profiles.find(
            new Query(Criteria.where("_class").is("org.sipfoundry.commons.diddb.Did")), Did.class);
    }

    @Override
    public List<Did> searchDidsByValue(String value) {
        return m_profiles.find(
            new Query(Criteria.where("_class").is("org.sipfoundry.commons.diddb.Did").and("value").regex(value)), Did.class);
    }

    @Override
    public List<Did> searchDidsByExtension(String extension) {
        return m_profiles.find(
            new Query(Criteria.where("_class").is("org.sipfoundry.commons.diddb.Did").and("typeId").regex(extension)), Did.class);
    }



    @Override
    public List<Did> getDidsExceptOne(String typeId) {
        return m_profiles.find(
            new Query(Criteria.where("typeId").ne(typeId)), Did.class);        
    }

    @Override
    public boolean isDidInUse(String typeId, String value) {
        return !m_profiles.find(
            new Query(Criteria.where("activeNext").exists(false).and("typeId").ne(typeId).and("value").is(value)), Did.class).isEmpty();        
    }
    
    @Override
    public boolean isDidInUse(String value) {
        return !m_profiles.find(
            new Query(Criteria.where("activeNext").exists(false).and("value").is(value)), Did.class).isEmpty();
    }
    
    @Override
    public boolean areDidsInUse(String typeId, List<String> values) {
        return getDidsInUse(typeId, values).isEmpty();
    }
    
    @Override
    public List<Did> getDidsInUse(String typeId, List<String> values) {
        return m_profiles.find(
            new Query(Criteria.where("activeNext").exists(false).
                and("typeId").ne(typeId).and("value").in(values)), Did.class);
    }
    
    @Required
    public void setProfiles(MongoTemplate profiles) {
        m_profiles = profiles;
    }
    
    public DidPool getDidPool(String typeId) {
        Did did = m_profiles.findOne(
            new Query(Criteria.where("typeId").is(typeId)), Did.class);
        DidPool pool = m_profiles.findOne(
            new Query(Criteria.where("_id").is(did.getPoolId())), DidPool.class);
        return pool;            
    }
}
