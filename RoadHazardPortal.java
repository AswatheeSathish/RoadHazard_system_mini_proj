import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.sql.*;
import java.util.Scanner;

public class RoadHazardPortal {

    // ========== CONFIGURE THESE ==========
    static final String DB_URL   = "jdbc:mysql://localhost:3306/road_hazard_db?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";
    static final String DB_USER  = "root";
    static final String DB_PASS  = "admk2204";      // change to your MySQL password
    static final int    PORT     = 8080;        // change if 8080 is busy

    // ============================================================
    //  MAIN
    // ============================================================
    public static void main(String[] args) {

        System.out.println("────────────────────────────────────");
        System.out.println("  Road Hazard Portal  —  starting…");
        System.out.println("────────────────────────────────────");

        // 1. Test DB connection
        try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
            System.out.println("✅ MySQL connection OK");
        } catch (SQLException e) {
            System.out.println("❌ MySQL connection FAILED: " + e.getMessage());
            return;
        }

        // 2. Start HTTP server
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

            server.createContext("/",         RoadHazardPortal::pageHome);
            server.createContext("/check",    RoadHazardPortal::pageCheck);
            server.createContext("/roads",    RoadHazardPortal::pageRoads);
            server.createContext("/hazards",  RoadHazardPortal::pageHazards);
            server.createContext("/severity", RoadHazardPortal::pageSeverity);
            server.createContext("/update",   RoadHazardPortal::pageUpdate);
            server.createContext("/delete",   RoadHazardPortal::pageDelete);

            server.setExecutor(null);
            server.start();

            System.out.println("🌐  http://localhost:" + PORT + "/");
            System.out.println("   Press ENTER here to shut down.");
            System.out.println("────────────────────────────────────");

        } catch (Exception e) {
            System.out.println("❌ Server failed to start:");
            e.printStackTrace();
            return;
        }

        new Scanner(System.in).nextLine();
        System.out.println("Server stopped.");
    }

    // ============================================================
    //  PAGE: HOME
    // ============================================================
    static void pageHome(HttpExchange ex) throws IOException {
        String content =
            "<h2>Check a Road</h2>" +
            "<form action='/check'>" +
            "<input name='name' placeholder='Enter road name…' required style='width:280px'>" +
            " <button>Check Safety</button></form>" +

            "<hr>" +
            "<h2>View Data</h2>" +
            "<a class='btn' href='/roads'>All Roads</a>" +
            "<a class='btn' href='/hazards'>All Hazards</a>" +
            "<a class='btn' href='/severity'>All Severities</a>" +

            "<hr>" +
            "<h2>Update a Value</h2>" +
            "<form action='/update'>" +
            "<select name='table'>" +
            "<option value='roads'>Road name</option>" +
            "<option value='hazards'>Hazard type</option>" +
            "<option value='severity'>Severity</option>" +
            "</select>" +
            " <input name='id' type='number' placeholder='ID' required style='width:80px'>" +
            " <input name='column' placeholder='column' required style='width:120px'>" +
            " <input name='value' placeholder='new value' required style='width:160px'>" +
            " <button>Update</button></form>" +
            "<small>e.g. column = road_name / hazard_type / severity</small>" +

            "<hr>" +
            "<h2>Delete a Row</h2>" +
            "<form action='/delete'>" +
            "<select name='table'>" +
            "<option value='hazards'>Hazard (hazard_id)</option>" +
            "<option value='severity'>Severity (road_id)</option>" +
            "<option value='roads'>Road (road_id, cascades)</option>" +
            "</select>" +
            " <input name='id' type='number' placeholder='ID' required style='width:80px'>" +
            " <button>Delete</button></form>";

        render(ex, "Home", content);
    }

    // ============================================================
    //  PAGE: CHECK
    // ============================================================
    static void pageCheck(HttpExchange ex) throws IOException {
        String roadName = getParam(ex.getRequestURI().getQuery(), "name");

        if (roadName == null || roadName.isBlank()) { redirect(ex, "/"); return; }

        StringBuilder out = new StringBuilder();
        out.append("<h2>Safety Report</h2>");
        out.append("<p>Search: <b>").append(esc(roadName)).append("</b></p>");

        String sql =
            "SELECT r.road_name, COALESCE(s.severity,'Unknown') AS severity, " +
            "       COUNT(h.hazard_id) AS hazard_count, " +
            "       GROUP_CONCAT(h.hazard_type SEPARATOR ', ') AS hazard_list " +
            "FROM roads r " +
            "LEFT JOIN severity s ON r.road_id = s.road_id " +
            "LEFT JOIN hazards  h ON r.road_id = h.road_id " +
            "WHERE LOWER(r.road_name) LIKE LOWER(?) " +
            "GROUP BY r.road_id, r.road_name, s.severity";

        try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {

            ps.setString(1, "%" + roadName + "%");
            ResultSet rs = ps.executeQuery();
            boolean any = false;

            while (rs.next()) {
                any = true;
                String road  = rs.getString("road_name");
                String sev   = rs.getString("severity");
                int count    = rs.getInt("hazard_count");
                String list  = rs.getString("hazard_list");

                out.append("<div class='report'>")
                   .append("<h3>").append(esc(road)).append("</h3>")
                   .append("<p><b>Severity:</b> ").append(esc(sev)).append("</p>")
                   .append("<p><b>Hazards:</b> ").append(count)
                   .append(" (").append(list == null ? "none" : esc(list)).append(")</p>")
                   .append("<p class='").append(verdictClass(sev)).append("'>")
                   .append(verdictText(sev)).append("</p></div>");
            }
            if (!any) out.append("<p class='unsafe'>No matching road found.</p>");
            rs.close();

        } catch (SQLException e) {
            out.append("<p class='unsafe'>DB error: ").append(esc(e.getMessage())).append("</p>");
        }

        render(ex, "Check", out + back());
    }

    // ============================================================
    //  PAGE: TABLES
    // ============================================================
    static void pageRoads(HttpExchange ex) throws IOException {
        render(ex, "Roads",
            table("All Roads",
                "SELECT road_id, road_name FROM roads ORDER BY road_id",
                new String[]{"road_id","road_name"}) + back());
    }

    static void pageHazards(HttpExchange ex) throws IOException {
        render(ex, "Hazards",
            table("All Hazards",
                "SELECT h.hazard_id, r.road_name, h.hazard_type " +
                "FROM hazards h JOIN roads r ON h.road_id = r.road_id ORDER BY h.hazard_id",
                new String[]{"hazard_id","road_name","hazard_type"}) + back());
    }

    static void pageSeverity(HttpExchange ex) throws IOException {
        render(ex, "Severity",
            table("All Severities",
                "SELECT s.severity_id, r.road_name, s.severity " +
                "FROM severity s JOIN roads r ON s.road_id = r.road_id ORDER BY s.severity_id",
                new String[]{"severity_id","road_name","severity"}) + back());
    }

    // ============================================================
    //  PAGE: UPDATE
    // ============================================================
    static void pageUpdate(HttpExchange ex) throws IOException {
        String q = ex.getRequestURI().getQuery();
        String table  = getParam(q, "table");
        String column = getParam(q, "column");
        String id     = getParam(q, "id");
        String value  = getParam(q, "value");

        StringBuilder out = new StringBuilder("<h2>Update Result</h2>");

        if (table == null || column == null || id == null || value == null) {
            out.append("<p class='unsafe'>Missing parameters.</p>");
        } else {
            String idCol = idColumn(table);
            if (idCol == null || !column.matches("[a-z_]+")) {
                out.append("<p class='unsafe'>Invalid table or column.</p>");
            } else {
                String sql = "UPDATE " + table + " SET " + column + " = ? WHERE " + idCol + " = ?";
                try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                     PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, value);
                    ps.setInt(2, Integer.parseInt(id));
                    int rows = ps.executeUpdate();
                    out.append("<p class='safe'>✅ Updated ").append(rows)
                       .append(" row(s) in <b>").append(esc(table)).append("</b>.</p>");
                } catch (SQLException | NumberFormatException e) {
                    out.append("<p class='unsafe'>Update failed: ")
                       .append(esc(e.getMessage())).append("</p>");
                }
            }
        }
        render(ex, "Update", out + back());
    }

    // ============================================================
    //  PAGE: DELETE
    // ============================================================
    static void pageDelete(HttpExchange ex) throws IOException {
        String q = ex.getRequestURI().getQuery();
        String table = getParam(q, "table");
        String id    = getParam(q, "id");

        StringBuilder out = new StringBuilder("<h2>Delete Result</h2>");

        if (table == null || id == null) {
            out.append("<p class='unsafe'>Missing parameters.</p>");
        } else {
            String idCol = idColumn(table);
            if (idCol == null) {
                out.append("<p class='unsafe'>Invalid table.</p>");
            } else {
                String sql = "DELETE FROM " + table + " WHERE " + idCol + " = ?";
                try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                     PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setInt(1, Integer.parseInt(id));
                    int rows = ps.executeUpdate();
                    out.append("<p class='safe'>✅ Deleted ").append(rows)
                       .append(" row(s) from <b>").append(esc(table)).append("</b>.</p>");
                } catch (SQLException | NumberFormatException e) {
                    out.append("<p class='unsafe'>Delete failed: ")
                       .append(esc(e.getMessage())).append("</p>");
                }
            }
        }
        render(ex, "Delete", out + back());
    }

    // ============================================================
    //  HELPERS
    // ============================================================
    static void render(HttpExchange ex, String title, String body) throws IOException {
        String html =
            "<!DOCTYPE html><html><head><meta charset='UTF-8'>" +
            "<title>" + esc(title) + " — Road Hazard Portal</title>" + style() +
            "</head><body>" +
            "<header><h1>🛣️ Road Hazard Portal</h1>" +
            "<a href='/'>← Home</a></header>" +
            "<main class='card'>" + body + "</main>" +
            "</body></html>";
        send(ex, 200, "text/html", html.getBytes());
    }

    static String table(String title, String sql, String[] cols) {
        StringBuilder sb = new StringBuilder("<h2>").append(title).append("</h2><table>");
        sb.append("<thead><tr>");
        for (String c : cols) sb.append("<th>").append(c).append("</th>");
        sb.append("</tr></thead><tbody>");

        try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                sb.append("<tr>");
                for (String col : cols)
                    sb.append("<td>").append(esc(rs.getString(col))).append("</td>");
                sb.append("</tr>");
            }
        } catch (SQLException e) {
            sb.append("<tr><td colspan='").append(cols.length)
              .append("' class='unsafe'>").append(esc(e.getMessage())).append("</td></tr>");
        }
        sb.append("</tbody></table>");
        return sb.toString();
    }

    static String back() {
        return "<p style='margin-top:24px'><a class='btn' href='/'>← Back to Home</a></p>";
    }

    static String idColumn(String table) {
        if ("roads".equals(table))    return "road_id";
        if ("hazards".equals(table))  return "hazard_id";
        if ("severity".equals(table)) return "road_id";
        return null;
    }

    static String verdictText(String sev) {
        if (sev == null) return "❓ UNKNOWN";
        String s = sev.toLowerCase();
        if (s.equals("critical") || s.equals("high")) return "🚫 NOT SAFE";
        if (s.equals("medium")) return "⚠️ CAUTION";
        if (s.equals("low"))    return "✅ SAFE";
        return "❓ UNKNOWN";
    }

    static String verdictClass(String sev) {
        if (sev == null) return "";
        String s = sev.toLowerCase();
        if (s.equals("critical") || s.equals("high")) return "unsafe";
        if (s.equals("medium")) return "caution";
        if (s.equals("low"))    return "safe";
        return "";
    }

    static String style() {
        return "<style>" +
            "*{box-sizing:border-box;font-family:system-ui,sans-serif}" +
            "body{margin:0;padding:24px;background:#f2f4f8;color:#1f2937}" +
            "header{background:linear-gradient(135deg,#1e3a8a,#2563eb);color:#fff;padding:24px;" +
            "border-radius:12px;margin-bottom:20px;box-shadow:0 4px 12px rgba(0,0,0,.1)}" +
            "header h1{margin:0 0 8px;font-size:28px}" +
            "header a{color:#dbeafe;text-decoration:none;font-size:14px}" +
            ".card{background:#fff;padding:24px;border-radius:12px;" +
            "box-shadow:0 2px 8px rgba(0,0,0,.06)}" +
            "input,select{padding:10px;border:1px solid #d1d5db;border-radius:8px;" +
            "margin:4px;font-size:14px}" +
            "button,.btn{display:inline-block;padding:10px 18px;margin:4px;border:none;" +
            "border-radius:8px;background:#2563eb;color:#fff;cursor:pointer;" +
            "text-decoration:none;font-size:14px;font-weight:500}" +
            "button:hover,.btn:hover{background:#1e40af}" +
            "table{width:100%;margin-top:12px;border-collapse:collapse;border-radius:8px;overflow:hidden}" +
            "th,td{padding:10px 14px;border-bottom:1px solid #e5e7eb;text-align:left;font-size:14px}" +
            "th{background:#eef2ff;font-weight:600;color:#1e3a8a}" +
            "tr:hover td{background:#f9fafb}" +
            "hr{border:none;border-top:1px solid #e5e7eb;margin:24px 0}" +
            ".safe{color:#15803d;font-weight:600}" +
            ".caution{color:#b45309;font-weight:600}" +
            ".unsafe{color:#b91c1c;font-weight:600}" +
            ".report{background:#f9fafb;border-left:4px solid #2563eb;padding:16px;" +
            "margin:16px 0;border-radius:8px}" +
            ".report h3{margin:0 0 8px;color:#1e3a8a}" +
            ".report p{margin:6px 0}" +
            "small{color:#6b7280;display:block;margin-top:6px}" +
            "</style>";
    }

    static String getParam(String query, String key) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                try { return URLDecoder.decode(kv[1], "UTF-8"); }
                catch (Exception e) { return kv[1]; }
            }
        }
        return null;
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    static void send(HttpExchange ex, int code, String mime, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", mime + "; charset=UTF-8");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().set("Location", location);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }
}