package org.testcontainers.jdbc;

import org.apache.commons.io.IOUtils;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.JdbcDatabaseContainerProvider;
import org.testcontainers.delegate.DatabaseDelegate;
import org.testcontainers.ext.ScriptUtils;
import org.testcontainers.utility.TestcontainersConfiguration;

import javax.script.ScriptException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Test Containers JDBC proxy driver. This driver will handle JDBC URLs of the form:
 * <p>
 * <code>jdbc:tc:<i>type</i>://<i>host</i>:<i>port</i>/<i>database</i>?<i>querystring</i></code>
 * <p>
 * where <i>type</i> is a supported database type (e.g. mysql, postgresql, oracle). Behind the scenes a new
 * docker container will be launched running the required database engine. New JDBC connections will be created
 * using the database's standard driver implementation, connected to the container.
 * <p>
 * If <code>TC_INITSCRIPT</code> is set in <i>querystring</i>, it will be used as the path for an init script that
 * should be run to initialize the database after the container is created. This should be a classpath resource.
 * <p>
 * Similarly <code>TC_INITFUNCTION</code> may be a method reference for a function that can initialize the database.
 * Such a function must accept a javax.sql.Connection as its only parameter.
 * An example of a valid method reference would be <code>com.myapp.SomeClass::initFunction</code>
 */
public class ContainerDatabaseDriver implements Driver {

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(ContainerDatabaseDriver.class);

    private Driver delegate;
    private static final Map<String, Set<Connection>> containerConnections = new HashMap<>();
    private static final Map<String, JdbcDatabaseContainer> jdbcUrlContainerCache = new HashMap<>();
    private static final Map<String, Map<String, String>> jdbcAliasMap = new HashMap<String, Map<String,String>>();
	
    private static final Set<String> initializedContainers = new HashSet<>();
    private static final String FILE_PATH_PREFIX = "file:";
    private static final String JDBC_ALIAS_PREFIX = "jdbc.alias.";

    static {
        load();
    }
    
    /**
     * Parse properties in default constructor
     */
    public ContainerDatabaseDriver() {
    	super();
    	mapJBDCAliasProperties(TestcontainersConfiguration.getInstance().getProperties());    	
	}

    private static void load() {
        try {
            DriverManager.registerDriver(new ContainerDatabaseDriver());
        } catch (SQLException e) {
            LOGGER.warn("Failed to register driver", e);
        }
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return url.startsWith("jdbc:tc:");
    }

    @Override
    public synchronized Connection connect(String url, final Properties info) throws SQLException {

        /*
          The driver should return "null" if it realizes it is the wrong kind of driver to connect to the given URL.
         */
        if (!acceptsURL(url)) {
            return null;
        }

        ConnectionUrl connectionUrl = ConnectionUrl.newInstance(url);

        synchronized (jdbcUrlContainerCache) {
        	
        	String queryString = connectionUrl.getQueryString().orElse("");
            /*
              If we already have a running container for this exact connection string, we want to connect
              to that rather than create a new container
             */
            JdbcDatabaseContainer container = jdbcUrlContainerCache.get(connectionUrl.getUrl());
            if (container == null) {

                LOGGER.debug("Container not found in cache, creating new instance");

                Map<String, String> parameters = connectionUrl.getContainerParameters();

                /*
                  Find a matching container type using ServiceLoader.
                 */
                ServiceLoader<JdbcDatabaseContainerProvider> databaseContainers = ServiceLoader.load(JdbcDatabaseContainerProvider.class);
                for (JdbcDatabaseContainerProvider candidateContainerType : databaseContainers) {
                	
                	String type = connectionUrl.getDatabaseType();
                	boolean wasAlias = false;
                	Map<String, String> aliasMap = new HashMap<String, String>();
                	if (jdbcAliasMap.containsKey(type)) { //this is an alias
                		aliasMap = jdbcAliasMap.get(type);
                		type = aliasMap.get("type"); //overwrite type from url with type from alias definition
                		wasAlias = true;
                	}
                	
                    if (candidateContainerType.supports(type)) {
                    	
                    	container = candidateContainerType.newInstance(connectionUrl);
                    	
                    	if (wasAlias) { //overwrite image and tag
                    		container.setDockerImageName(aliasMap.get("image")+":"+getAliasTag(aliasMap, container));                        	
                    	}
                    	LOGGER.debug("Starting container: "+container.getDockerImageName());
                    	container.withTmpFs(connectionUrl.getTmpfsOptions());
                        delegate = container.getJdbcDriverInstance();
                    }
                }
                if (container == null) {
                    throw new UnsupportedOperationException("Database name " + connectionUrl.getDatabaseType() + " not supported");
                }

                /*
                  Cache the container before starting to prevent race conditions when a connection
                  pool is started up
                 */
                jdbcUrlContainerCache.put(url, container);

                /*
                  Pass possible container-specific parameters
                 */
                container.setParameters(parameters);

                /*
                  Start the container
                 */
                container.start();
            }

            /*
              Create a connection using the delegated driver. The container must be ready to accept connections.
             */
            Connection connection = container.createConnection(queryString);

            /*
              If this container has not been initialized, AND
              an init script or function has been specified, use it
             */
            if (!initializedContainers.contains(container.getContainerId())) {
                DatabaseDelegate databaseDelegate = new JdbcDatabaseDelegate(container, queryString);
                runInitScriptIfRequired(connectionUrl, databaseDelegate);
                runInitFunctionIfRequired(connectionUrl, connection);
                initializedContainers.add(container.getContainerId());
            }

            return wrapConnection(connection, container, connectionUrl);
        }
    }


    /**
     * Wrap the connection, setting up a callback to be called when the connection is closed.
     * <p>
     * When there are no more open connections, the container itself will be stopped.
     *
     * @param connection    the new connection to be wrapped
     * @param container     the container which the connection is associated with
     * @param connectionUrl {@link ConnectionUrl} instance representing JDBC Url for this connection
     * @return the connection, wrapped
     */
    private Connection wrapConnection(final Connection connection, final JdbcDatabaseContainer container, final ConnectionUrl connectionUrl) {

        final boolean isDaemon = connectionUrl.isInDaemonMode() || connectionUrl.isReusable();

        Set<Connection> connections = containerConnections.computeIfAbsent(container.getContainerId(), k -> new HashSet<>());

        connections.add(connection);

        final Set<Connection> finalConnections = connections;

        return new ConnectionWrapper(connection, () -> {
            finalConnections.remove(connection);
            if (!isDaemon && finalConnections.isEmpty()) {
                container.stop();
                jdbcUrlContainerCache.remove(connectionUrl.getUrl());
            }
        });
    }

    /**
     * Run an init script from the classpath.
     *
     * @param connectionUrl    {@link ConnectionUrl} instance representing JDBC Url with init script.
     * @param databaseDelegate database delegate to apply init scripts to the database
     * @throws SQLException on script or DB error
     */
    private void runInitScriptIfRequired(final ConnectionUrl connectionUrl, DatabaseDelegate databaseDelegate) throws SQLException {
        if (connectionUrl.getInitScriptPath().isPresent()) {
            String initScriptPath = connectionUrl.getInitScriptPath().get();
            try {
                URL resource;
                if (initScriptPath.startsWith(FILE_PATH_PREFIX)) {
                    //relative workdir path
                    resource = new URL(initScriptPath);
                } else {
                    //classpath resource
                    resource = Thread.currentThread().getContextClassLoader().getResource(initScriptPath);
                }
                if (resource == null) {
                    LOGGER.warn("Could not load classpath init script: {}", initScriptPath);
                    throw new SQLException("Could not load classpath init script: " + initScriptPath + ". Resource not found.");
                }

                String sql = IOUtils.toString(resource, StandardCharsets.UTF_8);
                ScriptUtils.executeDatabaseScript(databaseDelegate, initScriptPath, sql);
            } catch (IOException e) {
                LOGGER.warn("Could not load classpath init script: {}", initScriptPath);
                throw new SQLException("Could not load classpath init script: " + initScriptPath, e);
            } catch (ScriptException e) {
                LOGGER.error("Error while executing init script: {}", initScriptPath, e);
                throw new SQLException("Error while executing init script: " + initScriptPath, e);
            }
        }
    }

    /**
     * Run an init function (must be a public static method on an accessible class).
     *
     * @param connectionUrl {@link ConnectionUrl} instance representing JDBC Url with r init function declarations.
     * @param connection    JDBC connection to apply init functions to.
     * @throws SQLException on script or DB error
     */
    private void runInitFunctionIfRequired(final ConnectionUrl connectionUrl, Connection connection) throws SQLException {
        if (connectionUrl.getInitFunction().isPresent()) {
            String className = connectionUrl.getInitFunction().get().getClassName();
            String methodName = connectionUrl.getInitFunction().get().getMethodName();

            try {
                Class<?> initFunctionClazz = Class.forName(className);
                Method method = initFunctionClazz.getMethod(methodName, Connection.class);

                method.invoke(null, connection);
            } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                LOGGER.error("Error while executing init function: {}::{}", className, methodName, e);
                throw new SQLException("Error while executing init function: " + className + "::" + methodName, e);
            }
        }
    }
    
    /**
     * Returns the tag for a JBDC alias if set or the default one from the implementing class 
     * @return tag
     */
    private String getAliasTag(Map<String, String> aliasMap, JdbcDatabaseContainer container) {
    	String tag = aliasMap.get("tag");
		if (tag == null) { // no tag was set, so use the default one
			String[] array = container.getDockerImageName().split(":");
			tag = array[1];
			
		}
		return tag;
    }
    
    /**
     * Maps relevant entries from testcontainers.properties to this class
     */
	public static void mapJBDCAliasProperties(Properties p) {
		for (String key : p.stringPropertyNames()) {

			if (key.startsWith(JDBC_ALIAS_PREFIX)) {

				String value = p.getProperty(key);
				LOGGER.debug("Recognized JDBC alias mapping: "+key+" = "+value);
				String relevantKeyPart = key.replaceFirst(JDBC_ALIAS_PREFIX, ""); // remove prefix
				String[] relevantKeyParts = relevantKeyPart.split("\\."); // 0 is alias, 1 is type,image or tag

				if (jdbcAliasMap.containsKey(relevantKeyParts[0])) { // alias already registered
					jdbcAliasMap.get(relevantKeyParts[0]).put(relevantKeyParts[1], value);
				} else {
					HashMap<String, String> childMap = new HashMap<String, String>();
					childMap.put(relevantKeyParts[1], value);
					jdbcAliasMap.put(relevantKeyParts[0], childMap);
				}
			}

		}
	}
	
	/**
	 * Clear all properties from configuration
	 */
	public static void clearJBDCAliasProperties() {		
		jdbcAliasMap.clear();
	}

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return delegate.getPropertyInfo(url, info);
    }

    @Override
    public int getMajorVersion() {
        return delegate.getMajorVersion();
    }

    @Override
    public int getMinorVersion() {
        return delegate.getMinorVersion();
    }
    
    @Override
    public boolean jdbcCompliant() {
        return delegate.jdbcCompliant();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    /**
     * Utility method to kill ALL database containers directly from test support code. It shouldn't be necessary to use this,
     * but it is provided for convenience - e.g. for situations where many different database containers are being
     * tested and cleanup is needed to limit resource usage.
     */
    public static void killContainers() {
        synchronized (jdbcUrlContainerCache) {
            jdbcUrlContainerCache.values().forEach(JdbcDatabaseContainer::stop);
            jdbcUrlContainerCache.clear();
            containerConnections.clear();
            initializedContainers.clear();
        }

    }

    /**
     * Utility method to kill a database container directly from test support code. It shouldn't be necessary to use this,
     * but it is provided for convenience - e.g. for situations where many different database containers are being
     * tested and cleanup is needed to limit resource usage.
     *
     * @param jdbcUrl the JDBC URL of the container which should be killed
     */
    public static void killContainer(String jdbcUrl) {
        synchronized (jdbcUrlContainerCache) {
            JdbcDatabaseContainer container = jdbcUrlContainerCache.get(jdbcUrl);
            if (container != null) {
                container.stop();
                jdbcUrlContainerCache.remove(jdbcUrl);
                containerConnections.remove(container.getContainerId());
                initializedContainers.remove(container.getContainerId());
            }
        }
    }

    /**
     * Utility method to get an instance of a database container given its JDBC URL.
     *
     * @param jdbcUrl the JDBC URL of the container instance to get
     * @return an instance of database container or <code>null</code> if no container associated with JDBC URL
     */
    static JdbcDatabaseContainer getContainer(String jdbcUrl) {
        synchronized (jdbcUrlContainerCache) {
            return jdbcUrlContainerCache.get(jdbcUrl);
        }
    }
    
    /**
     * Register JDBC alias for custom JDBC-URL 
     * @param alias		name of the alias e.g "my-own-mysql"
     * @param type		driver type of the alias specified e.g. "mysql", "sqlserver", ...
     * @param image		string that represents the docker image e.g. "mysql", "customregistry/image name"
     * @param tag		string that represents the docker image tag e.g. "mytag", "4711", ..
     */
    public static void registerAlias(String alias, String type, String image, String tag) {		
    	registerAlias(alias, type, image);    	
    	ContainerDatabaseDriver.jdbcAliasMap.get(alias).put("tag", tag);			
	}
    
    /**
     * Register JDBC alias for custom JDBC-URL 
     * @param alias		name of the alias e.g "my-own-mysql"
     * @param type		driver type of the alias specified e.g. "mysql", "sqlserver", ...
     * @param image		string that represents the docker image e.g. "mysql", "customregistry/image name"
     */
    public static void registerAlias(String alias, String type, String image) {		
    	Map<String, String> valueMap = new HashMap<String, String>();
    	valueMap.put("type", type);
    	valueMap.put("image", image);
    	    	
    	ContainerDatabaseDriver.jdbcAliasMap.put(alias, valueMap);
	}
    
}
