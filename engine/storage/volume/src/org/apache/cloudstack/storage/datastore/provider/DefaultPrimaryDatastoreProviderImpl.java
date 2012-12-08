package org.apache.cloudstack.storage.datastore.provider;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreLifeCycle;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreProvider;
import org.apache.cloudstack.storage.datastore.DefaultPrimaryDataStore;
import org.apache.cloudstack.storage.datastore.PrimaryDataStore;
import org.apache.cloudstack.storage.datastore.configurator.PrimaryDataStoreConfigurator;
import org.apache.cloudstack.storage.datastore.configurator.validator.ProtocolValidator;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreProviderDao;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreProviderVO;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreVO;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.driver.DefaultPrimaryDataStoreDriverImpl;
import org.apache.cloudstack.storage.datastore.driver.PrimaryDataStoreDriver;
import org.apache.cloudstack.storage.datastore.lifecycle.DefaultPrimaryDataStoreLifeCycleImpl;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.cloud.dc.ClusterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.utils.exception.CloudRuntimeException;

@Component
public class DefaultPrimaryDatastoreProviderImpl implements PrimaryDataStoreProvider {
    private final String providerName = "default primary data store provider";
    protected PrimaryDataStoreDriver driver;
    private PrimaryDataStoreProviderVO providerVO;
    @Inject
    protected PrimaryDataStoreDao dataStoreDao;
    @Inject
    protected PrimaryDataStoreProviderDao providerDao;
    @Inject
    protected ClusterDao clusterDao;
    protected Map<String, PrimaryDataStoreConfigurator> configuratorMaps = new HashMap<String, PrimaryDataStoreConfigurator>();

    @Inject
    public DefaultPrimaryDatastoreProviderImpl(@Qualifier("defaultProvider") List<PrimaryDataStoreConfigurator> configurators) {
        for (PrimaryDataStoreConfigurator configurator : configurators) {
            String key = generateKey(configurator.getSupportedHypervisor(), configurator.getSupportedDataStoreType().toString());
            configuratorMaps.put(key, configurator);
        }
    }

    protected String generateKey(HypervisorType hypervisor, String poolType) {
        return hypervisor.toString().toLowerCase() + "_" + poolType.toString().toLowerCase();
    }

    @Override
    public PrimaryDataStore getDataStore(long dataStoreId) {
        PrimaryDataStoreVO dsv = dataStoreDao.findById(dataStoreId);
        if (dsv == null) {
            return null;
        }

        String key = dsv.getKey();

        PrimaryDataStoreConfigurator configurator = configuratorMaps.get(key);

        DefaultPrimaryDataStore dataStore = (DefaultPrimaryDataStore)configurator.getDataStore(dataStoreId);
        dataStore.setProvider(this);
        return dataStore;
    }

    @Override
    public PrimaryDataStore registerDataStore(Map<String, String> dsInfos) {
        String url = dsInfos.get("url");
        URI uri = null;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new CloudRuntimeException("invalid url: " + e.toString());
        }
        String protocol = uri.getScheme();
        Long cluster = null;
        try {
            cluster = Long.parseLong(dsInfos.get("clusterId"));
        } catch (NumberFormatException e) {
            throw new CloudRuntimeException("Failed to get clusterId");
        }
        ClusterVO clusterVO = clusterDao.findById(cluster);
        if (clusterVO == null) {
            throw new CloudRuntimeException("Can't find cluster: " + cluster); 
        }
        HypervisorType hypervisor = clusterVO.getHypervisorType();
        String key = generateKey(hypervisor, protocol);
        PrimaryDataStoreConfigurator configurator = configuratorMaps.get(key);
        if (configurator == null) {
            throw new CloudRuntimeException("can't find configurator from key: " + key);
        }

        ProtocolValidator validator = configurator.getValidator();
        validator.validate(dsInfos);

        PrimaryDataStoreVO dataStoreVO = new PrimaryDataStoreVO();
        dataStoreVO.setStorageProviderId(this.getId());
        dataStoreVO.setHostAddress(dsInfos.get("server"));
        dataStoreVO.setPath(dsInfos.get("path"));
        dataStoreVO.setPoolType(protocol);
        dataStoreVO.setPort(Integer.parseInt(dsInfos.get("port")));
        dataStoreVO.setKey(key);
        dataStoreVO.setName(dsInfos.get("name"));
        dataStoreVO = dataStoreDao.persist(dataStoreVO);

        DefaultPrimaryDataStore dataStore = (DefaultPrimaryDataStore)configurator.getDataStore(dataStoreVO.getId());
        dataStore.setProvider(this);
        
        PrimaryDataStoreLifeCycle lifeCycle = dataStore.getLifeCycle();
        lifeCycle.initialize(dsInfos);
        return getDataStore(dataStore.getId());
    }

    @Override
    public long getId() {
        return this.providerVO.getId();
    }

    @Override
    public boolean configure() {
        this.providerVO = providerDao.findByName(this.providerName);
        return true;
    }

    @Override
    public String getName() {
        return providerName;
    }

}
