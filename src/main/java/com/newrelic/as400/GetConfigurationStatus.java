package com.newrelic.as400;

import com.ibm.as400.access.AS400;
import com.ibm.as400.access.SystemStatus;
import com.newrelic.labs.utils.JDBCConnection;
import com.newrelic.labs.utils.Constants;
import com.newrelic.labs.utils.CommonUtil;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

// ----------------------------------------------------------------------
// GetConfigurationStatus
//
// Implements "silent problem" monitoring scenario #4 from the IT Jungle
// "Admin Alert: Seven Things You Should Be Monitoring On Your System"
// article: critical lines, controllers, or devices that aren't active.
// A line/controller/device that fails or is left varied off does not by
// itself post an inquiry message that demands a response - the resource
// just silently sits unavailable - so it needs to be actively monitored.
//
// Uses SYSTOOLS.CONFIGURATION_STATUS (SQL equivalent of the WRKCFGSTS
// command) to report the status of lines, controllers, network server
// descriptions and devices, flagging anything not in an active/varied
// on state.
// ----------------------------------------------------------------------
public class GetConfigurationStatus {
    String Version = "\"integration_version\":\"" + CommonUtil.getIntegrationVersion() + "\",";

    public GetConfigurationStatus() {
    }

    public int execute(AS400 as400, Map<String, String> args, StringBuffer response) {
        int returnValue = Constants.UNKNOWN;
        Statement stmt = null;
        ResultSet rs = null;

        Connection connection = null;
        try {
            String systemName = new SystemStatus(as400).getSystemName().trim();
            JDBCConnection JDBCConn = new JDBCConnection();
            connection = JDBCConn.getJDBCConnection(as400.getSystemName(), args.get("-U"), args.get("-P"), args.get("-SSL"));
            if (connection == null) {
                response.append(Constants.retrieveDataError + " - " + "Cannot get the JDBC connection");
                return returnValue;
            }
            stmt = connection.createStatement();
            // Only report resources that are not in a healthy/available state, since a
            // typical partition can have hundreds of configuration objects and we only
            // care about the ones representing a "silent problem".
            rs = stmt.executeQuery(
                    "SELECT CFGD, CFGTYPE, CFGSTATUS, TEXTDESC, JOBNAME " +
                    "FROM SYSTOOLS.CONFIGURATION_STATUS " +
                    "WHERE CFGSTATUS NOT IN ('ACTIVE', 'AVAILABLE', 'VARIED ON', 'OPERATIONAL', 'ON') " +
                    "ORDER BY CFGTYPE, CFGD FETCH FIRST 200 ROWS ONLY");
            if (rs == null) {
                response.append(Constants.retrieveDataError + " - " + "Cannot retrieve data from server");
                return returnValue;
            }

            StringBuilder jsonMetrics = new StringBuilder();
            jsonMetrics.append("[");

            while (rs.next()) {
                String cfgDescription = rs.getString("CFGD");
                String cfgType = rs.getString("CFGTYPE");
                String cfgStatus = rs.getString("CFGSTATUS");
                String textDescription = rs.getString("TEXTDESC");
                String jobName = rs.getString("JOBNAME");

                jsonMetrics.append("{")
                        .append("\"event_type\":\"AS400:ConfigurationStatusEvent\",")
                        .append("\"systemName\":\"").append(systemName).append("\",")
                        .append("\"hostName\":\"").append(CommonUtil.getHostName(as400)).append("\",")
                        .append("\"includeInIseriesEntity\":true,")
                        .append("\"entityType\":\"IBM_ISERIES\",")
                        .append("\"nr.entityType\":\"IBM_ISERIES\",")
                        .append("\"configurationDescription\":\"").append(jsonEscape(cfgDescription == null ? "" : cfgDescription.trim())).append("\",")
                        .append("\"configurationType\":\"").append(jsonEscape(cfgType == null ? "" : cfgType.trim())).append("\",")
                        .append("\"configurationStatus\":\"").append(jsonEscape(cfgStatus == null ? "" : cfgStatus.trim())).append("\",")
                        .append("\"textDescription\":\"").append(jsonEscape(textDescription == null ? "" : textDescription.trim())).append("\",")
                        .append("\"jobName\":\"").append(jsonEscape(jobName == null ? "" : jobName.trim())).append("\"")
                        .append("},");
            }

            // Remove the last comma and close the JSON array
            if (jsonMetrics.length() > 1) {
                jsonMetrics.setLength(jsonMetrics.length() - 1);
            }
            jsonMetrics.append("]");

            response.append("{")
                    .append("\"name\":\"com.newrelic.as400-configuration-status\",")
                    .append("\"protocol_version\":\"1\",")
                    .append(Version)
                    .append("\"metrics\":").append(jsonMetrics.toString()).append(",")
                    .append("\"inventory\":{},")
                    .append("\"events\":[]")
                    .append("}");

            returnValue = Constants.OK;
            return returnValue;
        } catch (Exception e) {
            response.setLength(0);
            response.append(Constants.retrieveDataException + " - " + e.toString());
            CommonUtil.printStack(e.getStackTrace(), response);
            CommonUtil.logError(args.get("-H"), this.getClass().getName(), e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                if (rs != null)
                    rs.close();
                if (stmt != null)
                    stmt.close();
                if (connection != null)
                    connection.close();
            } catch (SQLException e) {
                response.append(Constants.retrieveDataException + " - " + e.toString());
                e.printStackTrace();
            }
        }
        return returnValue;
    }

    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
            }
        }
        return escaped.toString();
    }

    public static void main(String[] args) {
        String strAs400 = System.getenv("AS400HOST");
        String strUser = System.getenv("USERID");
        String strPass = System.getenv("PASSWD");
        AS400 as400 = new AS400(strAs400, strUser, strPass);

        GetConfigurationStatus configurationStatus = new GetConfigurationStatus();
        Map<String, String> arguments = new HashMap<>();
        arguments.put("-U", strUser);
        arguments.put("-P", strPass);
        arguments.put("-SSL", "false");

        StringBuffer response = new StringBuffer();
        configurationStatus.execute(as400, arguments, response);

        System.out.println(response.toString());
    }
}
