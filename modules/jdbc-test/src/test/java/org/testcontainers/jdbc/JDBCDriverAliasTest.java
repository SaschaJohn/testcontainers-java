package org.testcontainers.jdbc;

import static java.util.Arrays.asList;
import static org.rnorth.visibleassertions.VisibleAssertions.assertEquals;
import static org.rnorth.visibleassertions.VisibleAssertions.assertTrue;

import java.sql.SQLException;
import java.util.EnumSet;

import org.apache.commons.dbutils.QueryRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

@RunWith(Parameterized.class)
public class JDBCDriverAliasTest {

    private enum Options {
        ScriptedSchema,
        CharacterSet,
        CustomIniFile,
        JDBCParams,
        PmdKnownBroken
    }

    @Parameter
    public String jdbcUrl;
    @Parameter(1)
    public EnumSet<Options> options;

    @Parameterized.Parameters(name = "{index} - {0}")
    public static Iterable<Object[]> data() {
        return asList(
            new Object[][]{
                {"jdbc:tc:mysqlserver://hostname:hostport;databaseName=databasename", EnumSet.noneOf(Options.class)},
                {"jdbc:tc:myownmysql://hostname/databasename", EnumSet.noneOf(Options.class)}
                
            });
    }
    
    @Test
    public void shouldInstanciateContainersAccordingToDynamicAliasDefinition() throws SQLException {
    	ContainerDatabaseDriver.clearJBDCAliasProperties();
    	ContainerDatabaseDriver.registerAlias("mysqlserver", "sqlserver", "mcmoe/mssqldocker", "latest"); //with image tag
    	ContainerDatabaseDriver.registerAlias("myownmysql", "mysql", "mysql"); //without image tag
        
    	try (HikariDataSource dataSource = getDataSource(jdbcUrl, 1)) {
    		performSimpleTest(dataSource);
        }
    }
    
    protected void performSimpleTest(HikariDataSource dataSource) throws SQLException {
        String query = "SELECT 1";
        if (jdbcUrl.startsWith("jdbc:tc:db2:")) {
            query = "SELECT 1 FROM SYSIBM.SYSDUMMY1";
        }

        boolean result = new QueryRunner(dataSource, options.contains(Options.PmdKnownBroken)).query(query, rs -> {
            rs.next();
            int resultSetInt = rs.getInt(1);
            assertEquals("A basic SELECT query succeeds", 1, resultSetInt);
            return true;
        });

        assertTrue("The database returned a record as expected", result);
    }
    
    protected HikariDataSource getDataSource(String jdbcUrl, int poolSize) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setConnectionTestQuery("SELECT 1");
        hikariConfig.setMinimumIdle(1);
        hikariConfig.setMaximumPoolSize(poolSize);

        return new HikariDataSource(hikariConfig);
    }
    
}
