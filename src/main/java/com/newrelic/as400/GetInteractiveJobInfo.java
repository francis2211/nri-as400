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
// GetInteractiveJobInfo
//
// Implements "silent problem" monitoring scenarios #6 and #7 from the
// IT Jungle "Admin Alert: Seven Things You Should Be Monitoring On Your
// System" article:
//   #6 - Interactive users consuming a large amount of CPU.
//   #7 - Interactive response time spiking.
// Both are "silent" in that a single interactive user hogging the CPU,
// or response times creeping up, will not raise an inquiry message on
// QSYSOPR - they just quietly degrade the experience for every other
// interactive user on the partition until someone notices.
//
// Uses QSYS2.ACTIVE_JOB_INFO() filtered to interactive jobs (subsystem
// QINTER by default, overridable) to report per-job CPU percentage and
// average interactive response time (ELAPSED_TOTAL_RESPONSE_TIME /
// ELAPSED_INTERACTION_COUNT).
// ----------------------------------------------------------------------
public class GetInteractiveJobInfo {
    String Version = "\"integration_version\":\"" + CommonUtil.getIntegrationVersion() + "\",";

    private static final String DEFAULT_SUBSYSTEM_FILTER = "QINTER";

    public GetInteractiveJobInfo() {
    }

    public int execute(AS400 as400, Map<String, String> args, StringBuffer response) {
        int returnValue = Constants.UNKNOWN;
        Statement stmt = null;
        ResultSet rs = null;

        Connection connection = null;
        try {
            String subsystemFilter = args.get("-SUBSYSTEM");
            if (subsystemFilter == null || subsystemFilter.trim().isEmpty()) {
                subsystemFilter = DEFAULT_SUBSYSTEM_FILTER;
            }

            String systemName = new SystemStatus(as400).getSystemName().trim();
            JDBCConnection JDBCConn = new JDBCConnection();
            connection = JDBCConn.getJDBCConnection(as400.getSystemName(), args.get("-U"), args.get("-P"), args.get("-SSL"));
            if (connection == null) {
                response.append(Constants.retrieveDataError + " - " + "Cannot get the JDBC connection");
                return returnValue;
            }
            stmt = connection.createStatement();
            rs = stmt.executeQuery(
                    "SELECT JOB_NAME, AUTHORIZATION_NAME, SUBSYSTEM, JOB_STATUS, " +
                    "ELAPSED_CPU_PERCENTAGE, ELAPSED_CPU_TIME, ELAPSED_TOTAL_RESPONSE_TIME, " +
                    "ELAPSED_INTERACTION_COUNT, ELAPSED_TOTAL_DISK_IO_COUNT " +
                    "FROM TABLE(QSYS2.ACTIVE_JOB_INFO(SUBSYSTEM_LIST_FILTER => '" + subsystemFilter.replace("'", "") + "', " +
                    "JOB_TYPE_FILTER => '*INTERACT')) X " +
                    "ORDER BY ELAPSED_CPU_PERCENTAGE DESC FETCH FIRST 100 ROWS ONLY");
            if (rs == null) {
                response.append(Constants.retrieveDataError + " - " + "Cannot retrieve data from server");
                return returnValue;
            }

            StringBuilder jsonMetrics = new StringBuilder();
            jsonMetrics.append("[");

            while (rs.next()) {
                String jobName = rs.getString("JOB_NAME");
                String authorizationName = rs.getString("AUTHORIZATION_NAME");
                String subsystem = rs.getString("SUBSYSTEM");
                String jobStatus = rs.getString("JOB_STATUS");
                double elapsedCpuPercentage = rs.getDouble("ELAPSED_CPU_PERCENTAGE");
                long elapsedCpuTime = rs.getLong("ELAPSED_CPU_TIME");
                long elapsedTotalResponseTime = rs.getLong("ELAPSED_TOTAL_RESPONSE_TIME");
                long elapsedInteractionCount = rs.getLong("ELAPSED_INTERACTION_COUNT");
                long elapsedTotalDiskIoCount = rs.getLong("ELAPSED_TOTAL_DISK_IO_COUNT");

                // Average response time per interaction, in seconds. ELAPSED_TOTAL_RESPONSE_TIME
                // is reported in milliseconds; guard against divide-by-zero when a job has had
                // no interactions since the last stats reset.
                double avgResponseTimeSeconds = 0.0;
                if (elapsedInteractionCount > 0) {
                    avgResponseTimeSeconds = (elapsedTotalResponseTime / 1000.0) / elapsedInteractionCount;
                }

                jsonMetrics.append("{")
                        .append("\"event_type\":\"AS400:InteractiveJobEvent\",")
                        .append("\"systemName\":\"").append(systemName).append("\",")
                        .append("\"hostName\":\"").append(CommonUtil.getHostName(as400)).append("\",")
                        .append("\"includeInIseriesEntity\":true,")
                        .append("\"entityType\":\"IBM_ISERIES\",")
                        .append("\"nr.entityType\":\"IBM_ISERIES\",")
                        .append("\"jobName\":\"").append(jsonEscape(jobName == null ? "" : jobName.trim())).append("\",")
                        .append("\"authorizationName\":\"").append(jsonEscape(authorizationName == null ? "" : authorizationName.trim())).append("\",")
                        .append("\"subsystem\":\"").append(jsonEscape(subsystem == null ? "" : subsystem.trim())).append("\",")
                        .append("\"jobStatus\":\"").append(jsonEscape(jobStatus == null ? "" : jobStatus.trim())).append("\",")
                        .append("\"elapsedCpuPercentage\":").append(elapsedCpuPercentage).append(",")
                        .append("\"elapsedCpuTimeMs\":").append(elapsedCpuTime).append(",")
                        .append("\"elapsedTotalResponseTimeMs\":").append(elapsedTotalResponseTime).append(",")
                        .append("\"elapsedInteractionCount\":").append(elapsedInteractionCount).append(",")
                        .append("\"avgResponseTimeSeconds\":").append(avgResponseTimeSeconds).append(",")
                        .append("\"elapsedTotalDiskIoCount\":").append(elapsedTotalDiskIoCount)
                        .append("},");
            }

            // Remove the last comma and close the JSON array
            if (jsonMetrics.length() > 1) {
                jsonMetrics.setLength(jsonMetrics.length() - 1);
            }
            jsonMetrics.append("]");

            response.append("{")
                    .append("\"name\":\"com.newrelic.as400-interactive-job-info\",")
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
        String strSubsystem = System.getenv("SUBSYSTEM");
        AS400 as400 = new AS400(strAs400, strUser, strPass);

        GetInteractiveJobInfo interactiveJobInfo = new GetInteractiveJobInfo();
        Map<String, String> arguments = new HashMap<>();
        arguments.put("-U", strUser);
        arguments.put("-P", strPass);
        arguments.put("-SSL", "false");
        arguments.put("-SUBSYSTEM", strSubsystem);

        StringBuffer response = new StringBuffer();
        interactiveJobInfo.execute(as400, arguments, response);

        System.out.println(response.toString());
    }
}
