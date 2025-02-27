// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.common;

import static play.libs.Json.newObject;
import static play.libs.Json.toJson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.yugabyte.yw.common.config.RuntimeConfigFactory;
import com.yugabyte.yw.forms.DatabaseSecurityFormData;
import com.yugabyte.yw.forms.DatabaseUserFormData;
import com.yugabyte.yw.forms.RunQueryFormData;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.CommonUtils;
import com.yugabyte.yw.models.helpers.NodeDetails;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.mvc.Http;

@Singleton
public class YsqlQueryExecutor {
  private static final Logger LOG = LoggerFactory.getLogger(YsqlQueryExecutor.class);
  private static final String DEFAULT_DB_USER = Util.DEFAULT_YSQL_USERNAME;
  private static final String DEFAULT_DB_PASSWORD = Util.DEFAULT_YSQL_PASSWORD;
  private static final String DB_ADMIN_ROLE_NAME = Util.DEFAULT_YSQL_ADMIN_ROLE_NAME;
  private static final String PRECREATED_DB_ADMIN = "yb_db_admin";

  private static final String DEL_PG_ROLES_CMD_1 =
      "SET YB_NON_DDL_TXN_FOR_SYS_TABLES_ALLOWED=ON; "
          + "DELETE FROM pg_shdepend WHERE refclassid IN "
          + "(SELECT oid FROM pg_class WHERE relname='pg_authid')"
          + " AND refobjid IN (SELECT oid FROM pg_roles WHERE rolname IN "
          + "('pg_execute_server_program', 'pg_read_server_files', "
          + "'pg_write_server_files'));";
  private static final String DEL_PG_ROLES_CMD_2 =
      "SET YB_NON_DDL_TXN_FOR_SYS_TABLES_ALLOWED=ON; "
          + "DROP ROLE IF EXISTS pg_execute_server_program, pg_read_server_files, "
          + "pg_write_server_files; "
          + "UPDATE pg_yb_catalog_version "
          + "SET current_version = current_version + 1 WHERE db_oid = 1;";

  RuntimeConfigFactory runtimeConfigFactory;
  NodeUniverseManager nodeUniverseManager;

  @Inject
  public YsqlQueryExecutor(
      RuntimeConfigFactory runtimeConfigFactory, NodeUniverseManager nodeUniverseManager) {
    this.runtimeConfigFactory = runtimeConfigFactory;
    this.nodeUniverseManager = nodeUniverseManager;
  }

  private String wrapJsonAgg(String query) {
    return String.format("SELECT jsonb_agg(x) FROM (%s) as x", query);
  }

  private String getQueryType(String queryString) {
    String[] queryParts = queryString.split(" ");
    String command = queryParts[0].toUpperCase();
    if (command.equals("TRUNCATE") || command.equals("DROP"))
      return command + " " + queryParts[1].toUpperCase();
    return command;
  }

  // TODO This is a temporary workaround until it is fixed in the server side.
  private String removeQueryFromErrorMessage(String errMsg, String queryString) {
    // An error message contains the actual query sent to the server.
    if (errMsg != null) {
      errMsg = errMsg.replace(queryString, "<Query>");
    }
    return errMsg;
  }

  private List<Map<String, Object>> resultSetToMap(ResultSet result) throws SQLException {
    List<Map<String, Object>> rows = new ArrayList<>();
    ResultSetMetaData rsmd = result.getMetaData();
    int columnCount = rsmd.getColumnCount();

    while (result.next()) {
      // Represent a row in DB. Key: Column name, Value: Column value
      Map<String, Object> row = new HashMap<>();
      for (int i = 1; i <= columnCount; i++) {
        // Note that the index is 1-based
        String colName = rsmd.getColumnName(i);
        Object colVal = result.getObject(i);
        row.put(colName, colVal);
      }
      rows.add(row);
    }
    return rows;
  }

  public JsonNode executeQuery(Universe universe, RunQueryFormData queryParams) {
    return executeQuery(universe, queryParams, DEFAULT_DB_USER, DEFAULT_DB_PASSWORD);
  }

  public JsonNode executeQuery(
      Universe universe, RunQueryFormData queryParams, String username, String password) {
    ObjectNode response = newObject();

    // TODO: implement execute query for CQL
    String ysqlEndpoints = universe.getYSQLServerAddresses();
    String connectString =
        String.format("jdbc:postgresql://%s/%s", ysqlEndpoints.split(",")[0], queryParams.db_name);
    Properties props = new Properties();
    props.put("user", username);
    props.put("password", password);
    String caCert = universe.getCertificateClientToNode();
    if (caCert != null) {
      // Using verify CA since it is possible that the CN for the server cert
      // is the loadbalancer DNS/some generic ID. Just verifying the cert
      // is good enough.
      props.put("sslmode", "verify-ca");
      props.put("sslrootcert", caCert);
    }
    try (Connection conn = DriverManager.getConnection(connectString, props)) {
      if (conn == null) {
        response.put("error", "Unable to connect to DB");
      } else {
        PreparedStatement p = conn.prepareStatement(queryParams.query);
        boolean hasResult = p.execute();
        if (hasResult) {
          ResultSet result = p.getResultSet();
          List<Map<String, Object>> rows = resultSetToMap(result);
          response.set("result", toJson(rows));
        } else {
          response
              .put("queryType", getQueryType(queryParams.query))
              .put("count", p.getUpdateCount());
        }
      }
    } catch (SQLException | RuntimeException e) {
      response.put("error", removeQueryFromErrorMessage(e.getMessage(), queryParams.query));
    }
    return response;
  }

  public JsonNode executeQueryInNodeShell(
      Universe universe, RunQueryFormData queryParams, NodeDetails node) {
    ObjectNode response = newObject();
    response.put("type", "ysql");
    String queryType = getQueryType(queryParams.query);
    String queryString =
        queryType.equals("SELECT") ? wrapJsonAgg(queryParams.query) : queryParams.query;

    ShellResponse shellResponse = new ShellResponse();
    try {
      shellResponse =
          nodeUniverseManager
              .runYsqlCommand(node, universe, queryParams.db_name, queryString)
              .processErrors("Ysql Query Execution Error");
    } catch (RuntimeException e) {
      response.put("error", ShellResponse.cleanedUpErrorMessage(e.getMessage()));
      return response;
    }
    try {
      ObjectMapper objectMapper = new ObjectMapper();
      if (queryType.equals("SELECT")) {
        JsonNode jsonNode =
            objectMapper.readTree(CommonUtils.extractJsonisedSqlResponse(shellResponse));
        response.set("result", jsonNode);
      } else {
        response.put("queryType", queryType);
        response.put("result", shellResponse.message);
      }

    } catch (Exception e) {
      LOG.error(e.getMessage(), e);
      response.put("error", "Failed to parse response: " + e.getMessage());
    }
    return response;
  }

  public JsonNode runQueryUtil(Universe universe, DatabaseUserFormData data, String query) {
    RunQueryFormData ysqlQuery = new RunQueryFormData();
    // Create user for customer YSQL.
    ysqlQuery.query = query;
    ysqlQuery.db_name = data.dbName;
    JsonNode ysqlResponse =
        executeQuery(universe, ysqlQuery, data.ysqlAdminUsername, data.ysqlAdminPassword);
    if (ysqlResponse.has("error")) {
      String errorMsg = ysqlResponse.get("error").asText();
      LOG.error("Error executing query: {}", errorMsg);
      throw new PlatformServiceException(Http.Status.BAD_REQUEST, errorMsg);
    }
    return ysqlResponse;
  }

  public void createUser(Universe universe, DatabaseUserFormData data) {

    boolean isCloudEnabled =
        runtimeConfigFactory.forUniverse(universe).getBoolean("yb.cloud.enabled");

    StringBuilder allQueries = new StringBuilder();
    String query;

    query =
        String.format(
            "CREATE USER \"%s\" INHERIT CREATEROLE CREATEDB LOGIN BYPASSRLS PASSWORD '%s'",
            data.username, Util.escapeSingleQuotesOnly(data.password));
    allQueries.append(String.format("%s; ", query));

    if (isCloudEnabled) {
      // Create admin role
      query =
          String.format(
              "CREATE ROLE \"%s\" INHERIT CREATEROLE CREATEDB BYPASSRLS", DB_ADMIN_ROLE_NAME);
      allQueries.append(String.format("%s; ", query));
      boolean versionMatch =
          universe.getVersions().stream().allMatch(v -> Util.compareYbVersions(v, "2.6.4.0") >= 0);
      String rolesList = "pg_read_all_stats, pg_signal_backend";
      if (versionMatch) {
        // yb_extension is only supported in recent YB versions 2.6.x >= 2.6.4 and >= 2.8.0
        // not including the odd 2.7.x, 2.9.x releases
        rolesList += ", yb_extension";
      }
      query =
          String.format(
              "GRANT %2$s TO \"%1$s\"; "
                  + "GRANT EXECUTE ON FUNCTION pg_stat_statements_reset TO \"%1$s\"; "
                  + "GRANT ALL ON DATABASE yugabyte, postgres TO \"%1$s\";",
              DB_ADMIN_ROLE_NAME, rolesList);
      allQueries.append(String.format("%s ", query));
      allQueries.append(String.format("%s ", DEL_PG_ROLES_CMD_1));

      try {
        runUserDbCommands(allQueries.toString(), data.dbName, universe);
        LOG.info("Created users and deleted dependencies");
      } catch (Exception e) {
        if (e.getMessage().contains("already exists")) {
          // User exists, we should still try and run the rest of the tasks
          // since they are idempotent.
          LOG.warn(String.format("User already exists, skipping...\n%s", e.getMessage()));
        } else {
          throw e;
        }
      }

      allQueries.setLength(0);
      allQueries.append(String.format("%s ", DEL_PG_ROLES_CMD_2));
      runUserDbCommands(allQueries.toString(), data.dbName, universe);
      LOG.info("Dropped unrequired roles");

      allQueries.setLength(0);

      versionMatch =
          universe
              .getVersions()
              .stream()
              .allMatch(v -> Util.compareYbVersions(v, "2.12.2.0-b31") >= 0);
      if (versionMatch) {
        query =
            String.format(
                "GRANT \"%s\" TO \"%s\" WITH ADMIN OPTION",
                PRECREATED_DB_ADMIN, DB_ADMIN_ROLE_NAME);
        allQueries.append(String.format("%s; ", query));
      }
      query =
          String.format(
              "GRANT \"%s\" TO \"%s\" WITH ADMIN OPTION", DB_ADMIN_ROLE_NAME, data.username);
      allQueries.append(String.format("%s; ", query));
    }
    query = "SELECT pg_stat_statements_reset();";
    allQueries.append(query);

    runUserDbCommands(allQueries.toString(), data.dbName, universe);
    LOG.info("Assigned permissions to the user");
  }

  private void runUserDbCommands(String query, String dbName, Universe universe) {
    NodeDetails nodeToUse;
    try {
      nodeToUse = CommonUtils.getServerToRunYsqlQuery(universe);
    } catch (IllegalStateException e) {
      throw new PlatformServiceException(
          Http.Status.INTERNAL_SERVER_ERROR, "DB not ready to create a user");
    }
    RunQueryFormData ysqlQuery = new RunQueryFormData();
    ysqlQuery.query = query;
    ysqlQuery.db_name = dbName;
    JsonNode ysqlResponse = executeQueryInNodeShell(universe, ysqlQuery, nodeToUse);
    if (ysqlResponse.has("error")) {
      throw new PlatformServiceException(
          Http.Status.BAD_REQUEST, ysqlResponse.get("error").asText());
    }
  }

  public void validateAdminPassword(Universe universe, DatabaseSecurityFormData data) {
    RunQueryFormData ysqlQuery = new RunQueryFormData();
    ysqlQuery.db_name = data.dbName;
    ysqlQuery.query = "SELECT 1";
    JsonNode ysqlResponse =
        executeQuery(universe, ysqlQuery, data.ysqlAdminUsername, data.ysqlAdminPassword);
    if (ysqlResponse.has("error")) {
      String errMsg = ysqlResponse.get("error").asText();
      // Actual message is "FATAL: password authentication failed for user".
      // Foolproof attempt to match words in order.
      if (errMsg.matches(".*\\bpassword\\b.+\\bauthentication\\b.+\\bfailed\\b.*")) {
        throw new PlatformServiceException(Http.Status.UNAUTHORIZED, errMsg);
      }
      throw new PlatformServiceException(Http.Status.BAD_REQUEST, errMsg);
    }
  }

  public void updateAdminPassword(Universe universe, DatabaseSecurityFormData data) {
    // Update admin user password YSQL.
    StringBuilder allQueries = new StringBuilder();
    String query =
        String.format(
            "ALTER USER \"%s\" WITH PASSWORD '%s'",
            data.ysqlAdminUsername, Util.escapeSingleQuotesOnly(data.ysqlAdminPassword));
    allQueries.append(String.format("%s; ", query));
    query = "SELECT pg_stat_statements_reset();";
    allQueries.append(query);
    runUserDbCommands(allQueries.toString(), data.dbName, universe);
  }
}
