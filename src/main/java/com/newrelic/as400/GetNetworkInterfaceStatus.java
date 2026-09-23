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
// GetNetworkInterfaceStatus
//
// Implements "silent problem" monitoring scenario #5 from the IT Jungle
// "Admin Alert: Seven Things You Should Be Monitoring On Your System"
// article: IP interfaces not active. An IP interface that fails or is
// left inactive after a config change does not post an inquiry message
// to QSYSOPR - traffic on that interface just silently stops - so it
// needs to be actively polled and alerted on.
//
// Uses QSYS2.NETSTAT_INTERFACE_INFO to report the status of every
// configured IPv4/IPv6 interface, flagging any that are not ACTIVE
// (excluding the loopback interface, which is expected to always be up
// but is not itself an operational concern).
// ----------------------------------------------------------------------
public class GetNetworkInterfaceStatus {
    String Version = "\"integration_version\":\"" + CommonUtil.getIntegrationVersion() + "\",";

    public GetNetworkInterfaceStatus() {
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
            rs = stmt.executeQuery(
                    "SELECT CONNECTION_TYPE, INTERNET_ADDRESS, LINE_DESCRIPTION, INTERFACE_STATUS, " +
                    "INTERFACE_LINE_TYPE, NETWORK_ADDRESS, SUBNET_MASK " +
                    "FROM QSYS2.NETSTAT_INTERFACE_INFO " +
                    "ORDER BY INTERFACE_STATUS, INTERNET_ADDRESS");
            if (rs == null) {
                response.append(Constants.retrieveDataError + " - " + "Cannot retrieve data from server");
                return returnValue;
            }

            StringBuilder jsonMetrics = new StringBuilder();
            jsonMetrics.append("[");

            while (rs.next()) {
                String connectionType = rs.getString("CONNECTION_TYPE");
                String internetAddress = rs.getString("INTERNET_ADDRESS");
                String lineDescription = rs.getString("LINE_DESCRIPTION");
                String interfaceStatus = rs.getString("INTERFACE_STATUS");
                String interfaceLineType = rs.getString("INTERFACE_LINE_TYPE");
                String networkAddress = rs.getString("NETWORK_ADDRESS");
                String subnetMask = rs.getString("SUBNET_MASK");

                boolean isActive = interfaceStatus != null && interfaceStatus.trim().equalsIgnoreCase("ACTIVE");

                jsonMetrics.append("{")
                        .append("\"event_type\":\"AS400:NetworkInterfaceEvent\",")
                        .append("\"systemName\":\"").append(systemName).append("\",")
                        .append("\"hostName\":\"").append(CommonUtil.getHostName(as400)).append("\",")
                        .append("\"includeInIseriesEntity\":true,")
                        .append("\"entityType\":\"IBM_ISERIES\",")
                        .append("\"nr.entityType\":\"IBM_ISERIES\",")
                        .append("\"connectionType\":\"").append(jsonEscape(connectionType == null ? "" : connectionType.trim())).append("\",")
                        .append("\"internetAddress\":\"").append(jsonEscape(internetAddress == null ? "" : internetAddress.trim())).append("\",")
                        .append("\"lineDescription\":\"").append(jsonEscape(lineDescription == null ? "" : lineDescription.trim())).append("\",")
                        .append("\"interfaceStatus\":\"").append(jsonEscape(interfaceStatus == null ? "" : interfaceStatus.trim())).append("\",")
                        .append("\"interfaceLineType\":\"").append(jsonEscape(interfaceLineType == null ? "" : interfaceLineType.trim())).append("\",")
                        .append("\"networkAddress\":\"").append(jsonEscape(networkAddress == null ? "" : networkAddress.trim())).append("\",")
                        .append("\"subnetMask\":\"").append(jsonEscape(subnetMask == null ? "" : subnetMask.trim())).append("\",")
                        .append("\"isActive\":").append(isActive)
                        .append("},");
            }

            // Remove the last comma and close the JSON array
            if (jsonMetrics.length() > 1) {
                jsonMetrics.setLength(jsonMetrics.length() - 1);
            }
            jsonMetrics.append("]");

            response.append("{")
                    .append("\"name\":\"com.newrelic.as400-network-interface-status\",")
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

        GetNetworkInterfaceStatus networkInterfaceStatus = new GetNetworkInterfaceStatus();
        Map<String, String> arguments = new HashMap<>();
        arguments.put("-U", strUser);
        arguments.put("-P", strPass);
        arguments.put("-SSL", "false");

        StringBuffer response = new StringBuffer();
        networkInterfaceStatus.execute(as400, arguments, response);

        System.out.println(response.toString());
    }
}
