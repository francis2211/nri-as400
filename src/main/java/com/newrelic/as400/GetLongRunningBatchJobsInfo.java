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
// GetLongRunningBatchJobsInfo
//
// Implements "silent problem" monitoring scenario #1 from the IT Jungle
// "Admin Alert: Seven Things You Should Be Monitoring On Your System"
// article: long-running batch jobs. A batch job that quietly runs far
// longer than expected (stuck in a loop, waiting on a lock, or just
// abnormally slow) rarely produces an error message - it just keeps
// running - so it needs to be actively monitored for and alerted on.
//
// Uses QSYS2.ACTIVE_JOB_INFO() to find active batch jobs (JOB_TYPE='BCH')
// whose elapsed run time exceeds a configurable threshold
// (LONG_RUNNING_BATCH_THRESHOLD_SECONDS, default 3600 seconds/1 hour).
// ----------------------------------------------------------------------
public class GetLongRunningBatchJobsInfo {
    String Version = "\"integration_version\":\"" + CommonUtil.getIntegrationVersion() + "\",";

    private static final long DEFAULT_THRESHOLD_SECONDS = 3600L;

    public GetLongRunningBatchJobsInfo() {
    }

    public int execute(AS400 as400, Map<String, String> args, StringBuffer response) {
        int returnValue = Constants.UNKNOWN;
        Statement stmt = null;
        ResultSet rs = null;

        Connection connection = null;
        try {
            long thresholdSeconds = DEFAULT_THRESHOLD_SECONDS;
            String thresholdArg = args.get("-THRESHOLD");
            if (thresholdArg != null && !thresholdArg.trim().isEmpty()) {
                try {
                    thresholdSeconds = Long.parseLong(thresholdArg.trim());
                } catch (NumberFormatException nfe) {
                    thresholdSeconds = DEFAULT_THRESHOLD_SECONDS;
                }
            }

            String systemName = new SystemStatus(as400).getSystemName().trim();
            JDBCConnection JDBCConn = new JDBCConnection();
            connection = JDBCConn.getJDBCConnection(as400.getSystemName(), args.get("-U"), args.get("-P"), args.get("-SSL"));
            if (connection == null) {
                response.append(Constants.retrieveDataError + " - " + "Cannot get the JDBC connection");
                return returnValue;
            }
            stmt = connection.createStatement();
            // JOB_ACTIVE_TIME is the timestamp the job became active; we derive elapsed
            // seconds in SQL so the threshold filter can be pushed down to the server.
            rs = stmt.executeQuery(
                    "SELECT JOB_NAME, SUBSYSTEM, JOB_STATUS, JOB_QUEUE, JOB_QUEUE_LIBRARY, " +
                    "AUTHORIZATION_NAME, FUNCTION_TYPE, FUNCTION, JOB_ACTIVE_TIME, " +
                    "TIMESTAMPDIFF(2, CAST((CURRENT_TIMESTAMP - JOB_ACTIVE_TIME) AS CHAR(22))) AS RUNNING_SECONDS, " +
                    "ELAPSED_CPU_PERCENTAGE " +
                    "FROM TABLE(QSYS2.ACTIVE_JOB_INFO(JOB_TYPE_FILTER => '*BATCH')) X " +
                    "WHERE JOB_ACTIVE_TIME IS NOT NULL " +
                    "AND TIMESTAMPDIFF(2, CAST((CURRENT_TIMESTAMP - JOB_ACTIVE_TIME) AS CHAR(22))) >= " + thresholdSeconds + " " +
                    "ORDER BY RUNNING_SECONDS DESC FETCH FIRST 50 ROWS ONLY");
            if (rs == null) {
                response.append(Constants.retrieveDataError + " - " + "Cannot retrieve data from server");
                return returnValue;
            }

            StringBuilder jsonMetrics = new StringBuilder();
            jsonMetrics.append("[");

            while (rs.next()) {
                String jobName = rs.getString("JOB_NAME");
                String subsystem = rs.getString("SUBSYSTEM");
                String jobStatus = rs.getString("JOB_STATUS");
                String jobQueue = rs.getString("JOB_QUEUE");
                String jobQueueLibrary = rs.getString("JOB_QUEUE_LIBRARY");
                String authorizationName = rs.getString("AUTHORIZATION_NAME");
                String functionType = rs.getString("FUNCTION_TYPE");
                String function = rs.getString("FUNCTION");
                long runningSeconds = rs.getLong("RUNNING_SECONDS");
                double elapsedCpuPercentage = rs.getDouble("ELAPSED_CPU_PERCENTAGE");

                jsonMetrics.append("{")
                        .append("\"event_type\":\"AS400:LongRunningBatchJobEvent\",")
                        .append("\"systemName\":\"").append(systemName).append("\",")
                        .append("\"hostName\":\"").append(CommonUtil.getHostName(as400)).append("\",")
                        .append("\"includeInIseriesEntity\":true,")
                        .append("\"entityType\":\"IBM_ISERIES\",")
                        .append("\"nr.entityType\":\"IBM_ISERIES\",")
                        .append("\"jobName\":\"").append(jsonEscape(jobName == null ? "" : jobName.trim())).append("\",")
                        .append("\"subsystem\":\"").append(jsonEscape(subsystem == null ? "" : subsystem.trim())).append("\",")
                        .append("\"jobStatus\":\"").append(jsonEscape(jobStatus == null ? "" : jobStatus.trim())).append("\",")
                        .append("\"jobQueue\":\"").append(jsonEscape(jobQueue == null ? "" : jobQueue.trim())).append("\",")
                        .append("\"jobQueueLibrary\":\"").append(jsonEscape(jobQueueLibrary == null ? "" : jobQueueLibrary.trim())).append("\",")
                        .append("\"authorizationName\":\"").append(jsonEscape(authorizationName == null ? "" : authorizationName.trim())).append("\",")
                        .append("\"functionType\":\"").append(jsonEscape(functionType == null ? "" : functionType.trim())).append("\",")
                        .append("\"function\":\"").append(jsonEscape(function == null ? "" : function.trim())).append("\",")
                        .append("\"runningSeconds\":").append(runningSeconds).append(",")
                        .append("\"thresholdSeconds\":").append(thresholdSeconds).append(",")
                        .append("\"elapsedCpuPercentage\":").append(elapsedCpuPercentage)
                        .append("},");
            }

            // Remove the last comma and close the JSON array
            if (jsonMetrics.length() > 1) {
                jsonMetrics.setLength(jsonMetrics.length() - 1);
            }
            jsonMetrics.append("]");

            response.append("{")
                    .append("\"name\":\"com.newrelic.as400-long-running-batch-jobs\",")
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
        String strThreshold = System.getenv("THRESHOLD");
        AS400 as400 = new AS400(strAs400, strUser, strPass);

        GetLongRunningBatchJobsInfo longRunningBatchJobsInfo = new GetLongRunningBatchJobsInfo();
        Map<String, String> arguments = new HashMap<>();
        arguments.put("-U", strUser);
        arguments.put("-P", strPass);
        arguments.put("-SSL", "false");
        arguments.put("-THRESHOLD", strThreshold);

        StringBuffer response = new StringBuffer();
        longRunningBatchJobsInfo.execute(as400, arguments, response);

        System.out.println(response.toString());
    }
}
