package org.learn.nosql.dif;

import com.datastax.driver.core.BatchStatement;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Metadata;
import com.datastax.driver.core.QueryLogger;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.SimpleStatement;
import com.datastax.driver.core.Statement;
import org.learn.nosql.utils.SystemConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.datastax.driver.core.querybuilder.QueryBuilder.*;

/* Responsibility: Abstraction to interact with the underlying data-store <br>
*/
public class DataStore {
    private Cluster _cluster ;
    private Session _session ;
    protected static final String DEFAULT_KEYSPACE       = "events" ;
    protected static final String DEFAULT_ENDPOINT       = "127.0.0.1" ;
    protected static final String DEFAULT_DS_SERVER_PORT = "9042" ;
    private final String CREATE_KS_STMT   = "CREATE KEYSPACE %S WITH replication = " +
            "{'class' : 'SimpleStrategy', 'replication_factor' : 1};";
    private final String CLUSTER_INFO_MSG_TEMPLATE = "Node: {} is located on rack: {} in data center: {}";
    private final String USE_QUERY_TEMPLATE = "USE %s";
    private final String LINE_SEPARATOR = System.lineSeparator();
    private static final Logger _logger = LoggerFactory.getLogger(DataStore.class);
    private static DataStore INSTANCE   = new DataStore();

    private static HashSet<String> getConfiguredClusterEndpoints() {
        List<String> clusterNodes = Arrays.asList(System.getProperty("endpoints", DEFAULT_ENDPOINT));
        return new HashSet<>(clusterNodes);
    }

    private static String getPort() {
        return System.getProperty("dsServerPort", DEFAULT_DS_SERVER_PORT);
    }

    private static String getApplicationKSName() {
        return System.getProperty("appKeyspace", DEFAULT_KEYSPACE);
    }

    private DataStore() {
        //----------------- initialize with defaults if properties not set --------------------//
        this(getConfiguredClusterEndpoints(), getPort(), getApplicationKSName());
    }

    //---- client injects the cluster endpoints and target keyspace to work on ---//
    private DataStore(Set<String> endpoints, String port, String keyspace) {
        this._cluster = connect(Integer.parseInt(port), endpoints.toArray(new String[endpoints.size()]));
        this._session = createSession(Optional.ofNullable(keyspace).orElse(DEFAULT_KEYSPACE));
        registerQueryLogger(_cluster);
        printClusterInfo(_cluster);
    }

    private void registerQueryLogger(Cluster cluster) {
        cluster.register(QueryLogger.builder().build());
    }

    //---- start with the principle of least access modifiers unless necessary ---//
    private Cluster connect(int port, String... endpoints) {
        return Cluster.builder().addContactPoints(endpoints).withPort(port).build();
    }

    private Session createSession(String keyspace) {
        Session clusterSession = null ;
        // --- first connect to the cluster with provided keyspace -- //
        try {
            clusterSession = _cluster.connect(keyspace);
            return clusterSession; // return connected session to target keyspace
        } catch(Exception cause) { // possibly the keyspace does not exists yet
            _logger.warn("Failed to connect to target keyspace [ {} ]",keyspace,cause.getMessage());

            // create cluster scoped session
            clusterSession = _cluster.connect();

            createKSIfNotExists(clusterSession,keyspace);

            if(switchToTargetKS(clusterSession,keyspace)) { return clusterSession ; }

            // --- do not forget to discard the default session
            clusterSession.close();
            // --- if not in target keyspace, have a session for the target keyspace
            clusterSession = _cluster.connect(keyspace);
        }
        return clusterSession;
    }

    private void printClusterInfo(Cluster cluster) {
        Metadata metadata = cluster.getMetadata();
        metadata.getAllHosts()
                .stream()
                .forEach(host -> _logger
                        .info(CLUSTER_INFO_MSG_TEMPLATE,host.getAddress(),host.getRack(),host.getDatacenter()));
    }

    private boolean switchToTargetKS(Session clusterSession, String targetKeyspace) {
        // --- switch to target keyspace
        clusterSession.execute(String.format(USE_QUERY_TEMPLATE, targetKeyspace));
        String sessionKeyspace = clusterSession.getLoggedKeyspace();

        // --- verify if the session is for the target keyspace
        if( null != sessionKeyspace && sessionKeyspace.equals(targetKeyspace) ) {
            _logger.info("Successfully switched to target keyspace [ {} ]...",targetKeyspace);
            return true ;
        } else {
            _logger.warn("Could not switch cluster session to target keyspace [ {} ] ...",targetKeyspace);
            return false ;
        }
    }

    private void createKSIfNotExists(Session session, String keyspace) {
        try {
            session.execute(String.format(CREATE_KS_STMT,keyspace));
            _logger.info("Successfully created non-existent target keyspace {}",keyspace);
        } catch (Exception cause) {
            //-- Log why keyspace creation failed ??
            _logger.error("Failed to create target keyspace [ {} ]",keyspace,cause);
            close();
        }
    }

    public Session getSession() {
        return _session ;
    }

    //----------- close the open session and disconnect from the cluster ----//
    protected void close() {
        try {
            if( null != _session ) { _session.close(); }
            if( null != _cluster ) { _cluster.close(); }
        } catch (Exception cause) {
            // log failures
            _logger.error("Error closing connection to cluster",cause);
        }
    }

    protected void execute(Statement stmt) {
        Objects.requireNonNull(stmt,"A non-null statement object is needed for execution");
        _logger.info("Executing statement: " + stmt);

        try {
            ResultSet resultSet = getSession().execute(stmt);
        } catch( Exception cause ) {
            // do nothing for now.
        }
    }

    public static void main(String[] args) {
        new SystemConfig().load("dsconfig.properties");
        DataStore ds = DataStore.INSTANCE;

        String qs1 =
                update(getApplicationKSName(),"activity").with(set("event", "movement"))
                                  .where(eq("code_used", "1400"))
                                  .getQueryString();

        String qs2 = update("activity").with(set("code_used", "7000"))
                                               .where(eq("event", "policy breached"))
                                               .getQueryString();

        SimpleStatement rl_stmt     = new SimpleStatement(qs1);
        SimpleStatement cln_stmt    = new SimpleStatement(qs2);

        BatchStatement bstmt = new BatchStatement();
        bstmt.add(rl_stmt).add(cln_stmt);
        ds.execute(bstmt);
        ds.close();
    }
}
