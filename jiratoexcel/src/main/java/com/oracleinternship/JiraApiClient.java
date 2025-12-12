package com.oracleinternship;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

public class JiraApiClient {

    private final String baseUrl;
    private final String token;
    private final boolean debug;
    private final HttpClient client;

    public JiraApiClient(String baseUrl, String token, boolean debug) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.token = token;
        this.debug = debug;

        this.client = HttpClient.newBuilder()
                .followRedirects(debug ? HttpClient.Redirect.NEVER : HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public String getIssue(String issueKey) throws IOException, InterruptedException {
        String url = baseUrl + "rest/api/2/issue/" + issueKey;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .header("User-Agent", "JiraApiClient/1.0")
                .GET()
                .build();

        if (debug) {
            System.out.println("=== Jira API Request ===");
            System.out.println("URL: " + url);
            System.out.println("Headers: " + request.headers());
        }

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (debug) {
            System.out.println("=== Jira API Response ===");
            System.out.println("Status: " + response.statusCode());
            printHeaders(response.headers());
            if (response.statusCode() == 302) {
                System.out.println("⚠️ Redirect detected to: " + response.headers().firstValue("location").orElse("<none>"));
            }
        }

        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch issue (" + response.statusCode() + "): " + response.body());
        }

        return response.body();
    }

    public String searchIssues(LocalDate startDate, LocalDate endDate) throws IOException, InterruptedException {
        return searchIssues(startDate, endDate, null);
    }

    public String searchIssues(LocalDate startDate, LocalDate endDate, String assignee) throws IOException, InterruptedException {
        return searchIssues(startDate, endDate, assignee, null);
    }

    public String searchIssues(LocalDate startDate, LocalDate endDate, String assignee, String project) throws IOException, InterruptedException {
        return searchIssues(startDate, endDate, assignee, project, null);
    }

    public String searchIssues(LocalDate startDate, LocalDate endDate, String assignee, String project, List<String> issueTypes) throws IOException, InterruptedException {
        return searchIssuesWithPagination(startDate, endDate, assignee, project, issueTypes);
    }

    private String searchIssuesWithPagination(LocalDate startDate, LocalDate endDate, String assignee, String project, List<String> issueTypes) throws IOException, InterruptedException {
        // Build JQL query for tickets created OR resolved within the date range, optional assignee, and optional project
        StringBuilder jqlBuilder = new StringBuilder();
        jqlBuilder.append("((created >= ").append(startDate.toString());
        jqlBuilder.append(" AND created <= ").append(endDate.toString());
        jqlBuilder.append(") OR (resolutiondate >= ").append(startDate.toString());
        jqlBuilder.append(" AND resolutiondate <= ").append(endDate.toString()).append("))");

        if (project != null && !project.trim().isEmpty() && !"All Projects".equals(project)) {
            // Escape single quotes in project name and wrap in quotes
            String escapedProject = project.replace("'", "\\'");
            jqlBuilder.append(" AND project = '").append(escapedProject).append("'");
        }

        if (assignee != null && !assignee.trim().isEmpty()) {
            // Escape single quotes in assignee name and wrap in quotes
            String escapedAssignee = assignee.replace("'", "\\'");
            jqlBuilder.append(" AND assignee = '").append(escapedAssignee).append("'");
        }

        // Add issue type filter if issue types are specified
        if (issueTypes != null && !issueTypes.isEmpty()) {
            jqlBuilder.append(" AND issuetype IN (");
            for (int i = 0; i < issueTypes.size(); i++) {
                if (i > 0) jqlBuilder.append(", ");
                // Escape single quotes in issue type names and wrap in quotes
                String escapedIssueType = issueTypes.get(i).replace("'", "\\'");
                jqlBuilder.append("'").append(escapedIssueType).append("'");
            }
            jqlBuilder.append(")");
        }

        String jql = jqlBuilder.toString();
        String encodedJql = URLEncoder.encode(jql, StandardCharsets.UTF_8);

        int maxResultsPerPage = 1000; // Request up to 1000 results per page
        String fields = "summary,status,assignee,issuetype,resolutiondate,created,priority,customfield_27101,customfield_10704,issuelinks";

        ObjectMapper mapper = new ObjectMapper();
        ArrayNode allIssues = mapper.createArrayNode();
        int startAt = 0;
        int totalFetched = 0;

        while (true) {
            String url = baseUrl + "rest/api/2/search?jql=" + encodedJql + "&startAt=" + startAt + "&maxResults=" + maxResultsPerPage + "&fields=" + URLEncoder.encode(fields, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .header("User-Agent", "JiraApiClient/1.0")
                    .GET()
                    .build();

            if (debug) {
                System.out.println("=== Jira Search API Request (Page starting at " + startAt + ") ===");
                System.out.println("URL: " + url);
                System.out.println("JQL: " + jql);
                System.out.println("Headers: " + request.headers());
            }

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (debug) {
                System.out.println("=== Jira Search API Response ===");
                System.out.println("Status: " + response.statusCode());
                printHeaders(response.headers());
                if (response.statusCode() == 302) {
                    System.out.println("⚠️ Redirect detected to: " + response.headers().firstValue("location").orElse("<none>"));
                }
            }

            if (response.statusCode() != 200) {
                throw new IOException("Failed to search issues (" + response.statusCode() + "): " + response.body());
            }

            // Check if response is actually JSON
            String contentType = response.headers().firstValue("content-type").orElse("");
            if (!contentType.contains("application/json")) {
                throw new IOException("Expected JSON response but got: " + contentType + ". Response body: " + response.body().substring(0, Math.min(500, response.body().length())));
            }

            // Basic check if response starts with JSON
            String body = response.body().trim();
            if (!body.startsWith("{") && !body.startsWith("[")) {
                throw new IOException("Response does not appear to be valid JSON. Response body starts with: " + body.substring(0, Math.min(100, body.length())));
            }

            JsonNode root = mapper.readTree(body);
            int total = root.get("total").asInt();
            JsonNode issues = root.get("issues");

            if (issues != null && issues.isArray()) {
                allIssues.addAll((ArrayNode) issues);
                int issuesFetched = issues.size();
                totalFetched += issuesFetched;

                if (debug) {
                    System.out.println("Fetched " + issuesFetched + " issues in this page. Total fetched so far: " + totalFetched + " out of " + total);
                }

                // If we've fetched all issues or if no more issues in this response, break
                if (totalFetched >= total || issuesFetched == 0) {
                    break;
                }

                // Move to next page
                startAt += issuesFetched;
            } else {
                break; // No issues array, something went wrong
            }
        }

        // Create final combined response
        ObjectNode finalRoot = mapper.createObjectNode();
        finalRoot.put("total", allIssues.size());
        finalRoot.put("maxResults", allIssues.size());
        finalRoot.put("startAt", 0);
        finalRoot.set("issues", allIssues);

        return mapper.writeValueAsString(finalRoot);
    }

    private void printHeaders(HttpHeaders headers) {
        headers.map().forEach((k, v) -> System.out.println(k + ": " + v));
    }


}
