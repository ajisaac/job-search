package co.aisaac.webapp;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Job {

    // Every status a job can have, in the order they're shown.
    public static final List<String> STATUSES = List.of(
            "new", "declined", "interested", "later", "applied", "interviewing", "rejected"
    );

    // status -> keyboard shortcut on the main page.
    public static final Map<String, String> STATUS_KEYS = Map.of(
            "new", "n",
            "declined", "d",
            "interested", "i",
            "later", "l",
            "applied", "a",
            "interviewing", "v",
            "rejected", "r"
    );

    // job_site -> label, in the order they're shown.
    public static final Map<String, String> SITES;

    static {
        Map<String, String> sites = new LinkedHashMap<>();
        sites.put("indeed", "Indeed");
        sites.put("angelco", "Angel Co");
        sites.put("we_work_remotely", "We Work Remotely");
        sites.put("hacker_news", "Hacker News");
        sites.put("skip_the_drive", "Skip The Drive");
        sites.put("working_nomads", "Working Nomads");
        sites.put("remotive", "Remotive");
        sites.put("nodesk", "NoDesk");
        sites.put("jobspresso", "Jobspresso");
        sites.put("remoteok", "Remote OK");
        sites.put("himalayas", "Himalayas");
        sites.put("arcdev", "Arc.dev");
        sites.put("justremote", "JustRemote");
        sites.put("simplyhired", "SimplyHired");
        sites.put("dice", "Dice");
        sites.put("builtin", "Built In");
        // captured by the browser extension
        sites.put("linkedin", "LinkedIn");
        sites.put("wellfound", "Wellfound");
        sites.put("glassdoor", "Glassdoor");
        sites.put("work_at_a_startup", "Work at a Startup");
        sites.put("welcome_to_the_jungle", "Welcome to the Jungle");
        sites.put("remote_co", "Remote.co");
        sites.put("greenhouse", "Greenhouse");
        sites.put("lever", "Lever");
        sites.put("ashby", "Ashby");
        sites.put("workable", "Workable");
        sites.put("smartrecruiters", "SmartRecruiters");
        sites.put("recruitee", "Recruitee");
        SITES = Collections.unmodifiableMap(sites);
    }

    // "{scraper folder}/{file name}", e.g. "we_work_remotely/acme_senior-java-engineer_3f9a1c". Set by Jobs.
    public String id;

    public String title;
    public String url;
    public String company;

    public List<String> companyStatuses = new ArrayList<>();
    public String description;
    // The description as plain text, for searching. Set by Jobs, not saved.
    public String descriptionText;
    public String status;

    public String job_site;
    public LocalDate job_posting_date;
}
