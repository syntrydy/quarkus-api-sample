package org.gluu.apps.configapi;

import java.lang.annotation.Annotation;
import java.util.Properties;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.Initialized;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Instance;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.Produces;

import org.gluu.exception.OxIntializationException;
import org.gluu.oxauth.model.util.SecurityProviderUtility;
import org.gluu.oxtrust.config.ConfigurationFactory;
import org.gluu.oxtrust.service.ApplicationFactory;
import org.gluu.oxtrust.service.EncryptionService;
import org.gluu.oxtrust.service.cdi.event.CentralLdap;
import org.gluu.oxtrust.service.custom.LdapCentralConfigurationReload;
import org.gluu.persist.PersistenceEntryManager;
import org.gluu.persist.ldap.impl.LdapEntryManager;
import org.gluu.persist.model.PersistenceConfiguration;
import org.gluu.service.cdi.event.LdapConfigurationReload;
import org.gluu.service.cdi.util.CdiUtil;
import org.gluu.service.metric.inject.ReportMetric;
import org.gluu.util.StringHelper;
import org.gluu.util.properties.FileConfiguration;
import org.gluu.util.security.StringEncrypter;
import org.gluu.util.security.StringEncrypter.EncryptionException;
import org.slf4j.Logger;
import org.slf4j.bridge.SLF4JBridgeHandler;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
@Named
public class ConfigApiApplication {

	@Inject
	private Logger log;
	@Inject
	@Named(ApplicationFactory.PERSISTENCE_ENTRY_MANAGER_NAME)
	private Instance<PersistenceEntryManager> persistenceEntryManagerInstance;

	@Inject
	@Named(ApplicationFactory.PERSISTENCE_METRIC_ENTRY_MANAGER_NAME)
	@ReportMetric
	private Instance<PersistenceEntryManager> persistenceMetricEntryManagerInstance;

	@Inject
	@Named(ApplicationFactory.PERSISTENCE_CENTRAL_ENTRY_MANAGER_NAME)
	@CentralLdap
	private Instance<PersistenceEntryManager> persistenceCentralEntryManagerInstance;

	@Inject
	private Instance<EncryptionService> encryptionServiceInstance;

	@Inject
	private ApplicationFactory applicationFactory;

	@Inject
	private ConfigurationFactory configurationFactory;

	@Inject
	private BeanManager beanManager;

	// private static final Logger LOGGER =
	// Logger.getLogger(ConfigApiApplication.class);

	@PostConstruct
	public void createApplicationComponents() {
		SecurityProviderUtility.installBCProvider();
		SLF4JBridgeHandler.removeHandlersForRootLogger();
		SLF4JBridgeHandler.install();
	}

	public void applicationInitialized(@Observes @Initialized(ApplicationScoped.class) Object init) {
		log.debug("Initializing application services");
		configurationFactory.create();
		// PersistenceEntryManager localLdapEntryManager =
		// persistenceEntryManagerInstance.get();
		configurationFactory.initTimer();
	}

	protected Properties preparePersistanceProperties() {
		PersistenceConfiguration persistenceConfiguration = this.configurationFactory.getPersistenceConfiguration();
		FileConfiguration persistenceConfig = persistenceConfiguration.getConfiguration();
		Properties connectionProperties = (Properties) persistenceConfig.getProperties();
		EncryptionService securityService = encryptionServiceInstance.get();
		Properties decryptedConnectionProperties = securityService.decryptAllProperties(connectionProperties);
		return decryptedConnectionProperties;
	}

	protected Properties prepareCustomPersistanceProperties(String configId) {
		Properties connectionProperties = preparePersistanceProperties();
		if (StringHelper.isNotEmpty(configId)) {
			connectionProperties = (Properties) connectionProperties.clone();
			String baseGroup = configId + ".";
			for (Object key : connectionProperties.keySet()) {
				String propertyName = (String) key;
				if (propertyName.startsWith(baseGroup)) {
					propertyName = propertyName.substring(baseGroup.length());

					Object value = connectionProperties.get(key);
					connectionProperties.put(propertyName, value);
				}
			}
		}
		return connectionProperties;
	}

	@Produces
	@ApplicationScoped
	@Named(ApplicationFactory.PERSISTENCE_METRIC_ENTRY_MANAGER_NAME)
	@ReportMetric
	public PersistenceEntryManager createMetricPersistenceEntryManager() {
		Properties connectionProperties = prepareCustomPersistanceProperties(
				ApplicationFactory.PERSISTENCE_METRIC_CONFIG_GROUP_NAME);
		PersistenceEntryManager persistenceEntryManager = applicationFactory.getPersistenceEntryManagerFactory()
				.createEntryManager(connectionProperties);
		log.info("Created {}: {} with operation service: {}",
				new Object[] { ApplicationFactory.PERSISTENCE_METRIC_ENTRY_MANAGER_NAME, persistenceEntryManager,
						persistenceEntryManager.getOperationService() });
		return persistenceEntryManager;
	}

	@Produces
	@ApplicationScoped
	@Named(ApplicationFactory.PERSISTENCE_CENTRAL_ENTRY_MANAGER_NAME)
	@CentralLdap
	public PersistenceEntryManager createCentralLdapEntryManager() {
		if (!((configurationFactory.getLdapCentralConfiguration() != null)
				&& configurationFactory.getAppConfiguration().isUpdateStatus())) {
			return new LdapEntryManager();
		}
		FileConfiguration ldapCentralConfig = configurationFactory.getLdapCentralConfiguration();
		Properties centralConnectionProperties = (Properties) ldapCentralConfig.getProperties();
		EncryptionService securityService = encryptionServiceInstance.get();
		Properties decryptedCentralConnectionProperties = securityService
				.decryptProperties(centralConnectionProperties);
		PersistenceEntryManager centralLdapEntryManager = applicationFactory.getPersistenceEntryManagerFactory()
				.createEntryManager(decryptedCentralConnectionProperties);
		log.info("Created {}: {}", new Object[] { ApplicationFactory.PERSISTENCE_CENTRAL_ENTRY_MANAGER_NAME,
				centralLdapEntryManager.getOperationService() });
		return centralLdapEntryManager;
	}

	public void recreatePersistanceEntryManager(@Observes @LdapConfigurationReload String event) {
		recreatePersistanceEntryManagerImpl(persistenceEntryManagerInstance,
				ApplicationFactory.PERSISTENCE_ENTRY_MANAGER_NAME);
		recreatePersistanceEntryManagerImpl(persistenceEntryManagerInstance,
				ApplicationFactory.PERSISTENCE_METRIC_ENTRY_MANAGER_NAME, ReportMetric.Literal.INSTANCE);
	}

	protected void recreatePersistanceEntryManagerImpl(Instance<PersistenceEntryManager> instance,
			String persistenceEntryManagerName, Annotation... qualifiers) {
		PersistenceEntryManager oldLdapEntryManager = CdiUtil.getContextBean(beanManager, PersistenceEntryManager.class,
				persistenceEntryManagerName, qualifiers);
		closePersistenceEntryManager(oldLdapEntryManager, persistenceEntryManagerName);
		PersistenceEntryManager ldapEntryManager = instance.get();
		instance.destroy(ldapEntryManager);
		log.info("Recreated instance {}: {} with operation service: {}", persistenceEntryManagerName, ldapEntryManager,
				ldapEntryManager.getOperationService());
	}

	@Produces
	@ApplicationScoped
	@Named(ApplicationFactory.PERSISTENCE_ENTRY_MANAGER_NAME)
	public PersistenceEntryManager createPersistenceEntryManager() {
		Properties connectionProperties = preparePersistanceProperties();
		PersistenceEntryManager persistenceEntryManager = applicationFactory.getPersistenceEntryManagerFactory()
				.createEntryManager(connectionProperties);
		log.info("Created {}: {} with operation service: {}",
				new Object[] { ApplicationFactory.PERSISTENCE_ENTRY_MANAGER_NAME, persistenceEntryManager,
						persistenceEntryManager.getOperationService() });

		return persistenceEntryManager;
	}

	public void recreateCentralPersistanceEntryManager(@Observes @LdapCentralConfigurationReload String event) {
		PersistenceEntryManager oldCentralLdapEntryManager = CdiUtil.getContextBean(beanManager,
				PersistenceEntryManager.class, ApplicationFactory.PERSISTENCE_CENTRAL_ENTRY_MANAGER_NAME);
		closePersistenceEntryManager(oldCentralLdapEntryManager,
				ApplicationFactory.PERSISTENCE_CENTRAL_ENTRY_MANAGER_NAME);
		PersistenceEntryManager ldapCentralEntryManager = persistenceCentralEntryManagerInstance.get();
		persistenceEntryManagerInstance.destroy(ldapCentralEntryManager);
		log.info("Recreated instance {}: {}", ApplicationFactory.PERSISTENCE_CENTRAL_ENTRY_MANAGER_NAME,
				ldapCentralEntryManager);
	}

	private void closePersistenceEntryManager(PersistenceEntryManager oldPersistenceEntryManager,
			String persistenceEntryManagerName) {
		if ((oldPersistenceEntryManager != null) && (oldPersistenceEntryManager.getOperationService() != null)) {
			log.debug("Attempting to destroy {}:{} with operation service: {}", persistenceEntryManagerName,
					oldPersistenceEntryManager, oldPersistenceEntryManager.getOperationService());
			oldPersistenceEntryManager.destroy();
			log.debug("Destroyed {}:{} with operation service: {}", persistenceEntryManagerName,
					oldPersistenceEntryManager, oldPersistenceEntryManager.getOperationService());
		}
	}

	@Produces
	@ApplicationScoped
	public StringEncrypter getStringEncrypter() throws OxIntializationException {
		String encodeSalt = configurationFactory.getCryptoConfigurationSalt();
		if (StringHelper.isEmpty(encodeSalt)) {
			throw new OxIntializationException("Encode salt isn't defined");
		}
		try {
			StringEncrypter stringEncrypter = StringEncrypter.instance(encodeSalt);
			return stringEncrypter;
		} catch (EncryptionException ex) {
			throw new OxIntializationException("Failed to create StringEncrypter instance");
		}
	}

	void onStart(@Observes StartupEvent ev) {
		// LOGGER.info("CONFIG API -STARTUP PROCESS.");
		log.info("================================...");
		configurationFactory.create();
		configurationFactory.initTimer();

	}

	void onStop(@Observes ShutdownEvent ev) {
		// LOGGER.info("CONFIG API -SHUTDOWN PROCESS.");
		log.info("Stopping services and closing DB connections at server shutdown...");
		PersistenceEntryManager persistanceEntryManager = persistenceEntryManagerInstance.get();
		closePersistenceEntryManager(persistanceEntryManager, ApplicationFactory.PERSISTENCE_ENTRY_MANAGER_NAME);
		PersistenceEntryManager persistanceMetricEntryManager = persistenceMetricEntryManagerInstance.get();
		closePersistenceEntryManager(persistanceMetricEntryManager,
				ApplicationFactory.PERSISTENCE_METRIC_ENTRY_MANAGER_NAME);
		PersistenceEntryManager persistanceCentralEntryManager = persistenceCentralEntryManagerInstance.get();
		if (persistanceCentralEntryManager != null) {
			closePersistenceEntryManager(persistanceCentralEntryManager,
					ApplicationFactory.PERSISTENCE_CENTRAL_ENTRY_MANAGER_NAME);
		}
	}

}
