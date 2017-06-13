package com.indeed.jiraactions;

import com.fasterxml.jackson.databind.JsonNode;
import com.indeed.jiraactions.api.ApiUserLookupService;
import com.indeed.jiraactions.api.response.issue.Issue;
import com.indeed.jiraactions.api.IssueAPIParser;
import com.indeed.jiraactions.api.IssuesAPICaller;
import org.apache.log4j.Logger;
import org.joda.time.DateTime;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @author oono
 */
public class JiraActionsIndexBuilder {
    private static final Logger log = Logger.getLogger(JiraActionsIndexBuilder.class);

    private final JiraActionsIndexBuilderConfig config;

    public JiraActionsIndexBuilder(final JiraActionsIndexBuilderConfig config) {
        this.config = config;
    }

    public void run() throws Exception {
        try {
            final long start_total = System.currentTimeMillis();
            final long end_total;

            final ApiUserLookupService userLookupService = new ApiUserLookupService(config);
            final ActionFactory actionFactory = new ActionFactory(userLookupService);

            final IssuesAPICaller issuesAPICaller = new IssuesAPICaller(config);
            {
                final long start = System.currentTimeMillis();
                final int total = issuesAPICaller.setNumTotal();
                final long end = System.currentTimeMillis();
                log.info(String.format("%d ms, found %d total issues.", end - start, total));
            }

            long apiTime = 0;
            long processTime = 0;
            long fileTime = 0;

            long start, end;

            final DateTime startDate = JiraActionsUtil.parseDateTime(config.getStartDate());
            final DateTime endDate = JiraActionsUtil.parseDateTime(config.getEndDate());

            final TsvFileWriter writer = new TsvFileWriter(config);
            start = System.currentTimeMillis();
            writer.createFileAndWriteHeaders();
            end = System.currentTimeMillis();
            fileTime += end - start;

            final Set<String> seenIssues = new HashSet<>();

            while (issuesAPICaller.currentPageExist()) {
                start = System.currentTimeMillis();
                final JsonNode issuesNode = issuesAPICaller.getIssuesNodeWithBackoff();
                end = System.currentTimeMillis();
                apiTime += end - start;
                log.trace(String.format("%d ms for an API call.", end - start));

                start = System.currentTimeMillis();
                for (final JsonNode issueNode : issuesNode) {
                    // Parse Each Issue API response to Object.
                    long process_start = System.currentTimeMillis();
                    final Issue issue = IssueAPIParser.getObject(issueNode);
                    long process_end = System.currentTimeMillis();
                    processTime += process_end - process_start;
                    if(issue == null || seenIssues.contains(issue.key)) {
                        continue;
                    }
                    seenIssues.add(issue.key);

                    try {
                        // Build Action objects from parsed API response Object.
                        process_start = System.currentTimeMillis();
                        final ActionsBuilder actionsBuilder = new ActionsBuilder(actionFactory, issue, startDate, endDate);
                        final List<Action> actions = actionsBuilder.buildActions();
                        process_end = System.currentTimeMillis();
                        processTime += process_end - process_start;

                        // Set built actions to actions list.
                        final long file_start = System.currentTimeMillis();
                        writer.writeActions(actions);
                        final long file_end = System.currentTimeMillis();
                        fileTime += file_end - file_start;
                    } catch(final Exception e) {
                        log.error(String.format("Error parsing actions for issue %s.", issue.key), e);
                    }
                }
                end = System.currentTimeMillis();
                log.trace(String.format("%d ms to get actions from a set of issues.", end - start));
            }

            log.debug(String.format("Had to look up %d users.", userLookupService.numLookups()));

            start = System.currentTimeMillis();
            // Create and Upload a TSV file.
            writer.uploadTsvFile();
            end = System.currentTimeMillis();
            log.debug(String.format("%d ms to create and upload TSV.", end - start));
            fileTime += end - start;

            end_total = System.currentTimeMillis();

            final long apiUserTime = userLookupService.getUserLookupTotalTime();

            log.info(String.format("%d ms for the whole process.", end_total - start_total));
            log.info(String.format("apiTime: %dms, processTime: %dms, fileTime: %dms, userLookupTime: %dms",
                    apiTime-apiUserTime, processTime, fileTime, apiUserTime));
            log.warn(String.format("Potentially missed %d issues!", issuesAPICaller.getNumPotentiallySkipped()));
        } catch (final Exception e) {
            log.error("Threw an exception trying to run the index builder", e);
            throw e;
        }
    }
}
