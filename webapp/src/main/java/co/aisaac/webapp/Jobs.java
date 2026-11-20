package co.aisaac.webapp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Stores jobs as files, one folder per scraper:
 *
 * <pre>
 * jobs/
 *   we_work_remotely/
 *     acme_senior-java-engineer_3f9a1c.json   metadata
 *     acme_senior-java-engineer_3f9a1c.html   description
 * </pre>
 * <p>
 * Everything is loaded into memory on startup. Changes update memory and are written straight to disk.
 * Every read first picks up any files added since the last read, so jobs scraped while the webapp is running show up.
 * <p>
 * A job's id is "{scraper folder}/{file name without extension}", e.g. "we_work_remotely/acme_senior-java-engineer_3f9a1c".
 * File names are created once and never change. The description html is only written when a job is first saved.
 * Descriptions are sanitized (no scripts, styles or classes) before they're stored or shown.
 */
public class Jobs {

  private static final Safelist SAFELIST = Safelist.relaxed();

  private final Path root;
  private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  // id -> job
  private final Map<String, Job> jobs = new LinkedHashMap<>();
  // url -> id, to skip jobs that are already saved
  private final Map<String, String> idsByUrl = new HashMap<>();
  // "title company", to skip the same job posted on several sites
  private final Set<String> titlesAtCompanies = new HashSet<>();

  public Jobs() {
    this(Path.of("jobs"));
  }

  public Jobs(Path root) {
    this.root = root;
    loadNewFiles();
  }

  // ---------- reads ----------

  public synchronized List<Job> findAll() {
    loadNewFiles();
    return jobs.values().stream().map(Jobs::copy).toList();
  }

  public synchronized List<Job> findAllByStatusOrderByTitle(String status) {
    loadNewFiles();
    return jobs.values().stream()
        .filter(job -> status.equalsIgnoreCase(job.status))
        .sorted(Comparator.comparing(job -> job.title, String.CASE_INSENSITIVE_ORDER))
        .map(Jobs::copy)
        .toList();
  }

  public synchronized Optional<Job> findById(String id) {
    return Optional.ofNullable(jobs.get(id)).map(Jobs::copy);
  }

  public synchronized boolean exists(String url) {
    return idsByUrl.containsKey(url);
  }

  public synchronized boolean existsTitleAtCompany(String title, String company) {
    return titlesAtCompanies.contains(titleAtCompany(title, company));
  }

  // ---------- writes ----------

  /**
   * Saves a new job, or updates an existing one (matched by url).
   * For an existing job only the metadata is updated, the stored description is kept.
   *
   * @return true if the job was new
   */
  public synchronized boolean save(Job job) {
    String id = idsByUrl.get(job.url);

    if (id == null) {
      Job stored = copy(job);
      prepare(stored, folderName(job.job_site));
      stored.id = newId(stored);
      add(stored);
      writeHtml(stored.id, stored.description);
      writeJson(stored.id, stored);
      job.id = stored.id;
      return true;
    }

    Job stored = jobs.get(id);
    titlesAtCompanies.remove(titleAtCompany(stored.title, stored.company));
    stored.title = job.title;
    stored.company = job.company;
    stored.status = job.status;
    stored.job_site = job.job_site;
    stored.job_posting_date = job.job_posting_date;
    prepare(stored, id.substring(0, id.indexOf('/')));
    titlesAtCompanies.add(titleAtCompany(stored.title, stored.company));
    writeJson(id, stored);
    job.id = id;
    return false;
  }

  /**
   * For the scrapers: saves a job only if it's new. Skips it if a job with the same url exists,
   * or the same title at the same company (the same job posted on another site).
   * Jobs without a company are only matched by url, so two companies' "Senior Engineer" aren't mixed up.
   *
   * @return true if the job was saved
   */
  public synchronized boolean saveNew(Job job) {
    loadNewFiles();
    if (exists(job.url)) {
      return false;
    }
    if (job.company != null && !job.company.isBlank() && existsTitleAtCompany(job.title, job.company)) {
      return false;
    }
    return save(job);
  }

  /**
   * Adds a description to a saved job that doesn't have one yet, e.g. one first seen in a list of results.
   *
   * @return true if the description was added
   */
  public synchronized boolean fillDescription(String url, String description) {
    String id = idsByUrl.get(url);
    if (id == null || description == null || description.isBlank()) {
      return false;
    }

    Job stored = jobs.get(id);
    if (!stored.descriptionText.isBlank()) {
      return false;
    }

    stored.description = description;
    prepare(stored, id.substring(0, id.indexOf('/')));
    if (stored.descriptionText.isBlank()) {
      return false;
    }
    writeHtml(id, stored.description);
    return true;
  }

  // ---------- loading ----------

  // Loads any json files that aren't in memory yet. Only lists directories, so it's cheap to call often.
  private void loadNewFiles() {
    if (!Files.isDirectory(root)) {
      return;
    }

    try (Stream<Path> siteDirs = Files.list(root)) {
      for (Path siteDir : siteDirs.filter(Files::isDirectory).sorted().toList()) {
        String site = siteDir.getFileName().toString();

        try (Stream<Path> files = Files.list(siteDir)) {
          for (Path jsonFile : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
            String name = jsonFile.getFileName().toString().replaceFirst("\\.json$", "");
            String id = site + "/" + name;
            if (jobs.containsKey(id)) {
              continue;
            }

            Job job = readJson(jsonFile);
            Path htmlFile = siteDir.resolve(name + ".html");
            job.description = Files.exists(htmlFile) ? Files.readString(htmlFile, StandardCharsets.UTF_8) : "";
            job.id = id;
            prepare(job, site);
            add(job);
          }
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void add(Job job) {
    jobs.put(job.id, job);
    idsByUrl.put(job.url, job.id);
    titlesAtCompanies.add(titleAtCompany(job.title, job.company));
  }

  // No nulls anywhere, so the rest of the app doesn't need to check. Also sanitizes the description.
  private static void prepare(Job job, String site) {
    job.title = orEmpty(job.title);
    job.url = orEmpty(job.url);
    job.company = orEmpty(job.company);
    job.status = job.status == null || job.status.isBlank() ? "new" : job.status.trim().toLowerCase(Locale.ROOT);
    job.job_site = job.job_site == null || job.job_site.isBlank() ? site : job.job_site;

    Document clean = new Cleaner(SAFELIST).clean(Jsoup.parseBodyFragment(orEmpty(job.description)));
    job.description = clean.body().html();
    job.descriptionText = clean.body().text();
  }

  private static String orEmpty(String s) {
    return s == null ? "" : s;
  }

  private static String titleAtCompany(String title, String company) {
    return orEmpty(title).trim().toLowerCase(Locale.ROOT) + " " + orEmpty(company).trim().toLowerCase(Locale.ROOT);
  }

  // ---------- files ----------

  private Job readJson(Path file) throws IOException {
    Map<String, String> map = mapper.readValue(file.toFile(), new TypeReference<>() {
    });

    Job job = new Job();
    job.title = map.get("title");
    job.url = map.get("url");
    job.company = map.get("company");
    job.status = map.get("status");
    job.job_site = map.get("job_site");
    String date = map.get("job_posting_date");
    job.job_posting_date = date == null || date.isBlank() ? null : LocalDate.parse(date);
    return job;
  }

  private void writeJson(String id, Job job) {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("title", job.title);
    map.put("url", job.url);
    map.put("company", job.company);
    map.put("status", job.status);
    map.put("job_site", job.job_site);
    map.put("job_posting_date", job.job_posting_date == null ? null : job.job_posting_date.toString());

    try {
      writeAtomically(root.resolve(id + ".json"), mapper.writeValueAsString(map));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void writeHtml(String id, String description) {
    writeAtomically(root.resolve(id + ".html"), description);
  }

  // Write to a temp file then rename, so a half written file is never read.
  private void writeAtomically(Path file, String content) {
    try {
      Files.createDirectories(file.getParent());
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      Files.writeString(tmp, content, StandardCharsets.UTF_8);
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ---------- naming ----------

  // "we_work_remotely/acme_senior-java-engineer_3f9a1c"
  private String newId(Job job) {
    String site = folderName(job.job_site);
    String base = slug(job.company, 30) + "_" + slug(job.title, 60) + "_" + shortHash(job.url);

    String id = site + "/" + base;
    int n = 2;
    while (jobs.containsKey(id)) {
      id = site + "/" + base + "-" + n++;
    }
    return id;
  }

  // "Société Générale, Inc." -> "societe-generale-inc"
  private static String slug(String s, int maxLength) {
    String slug = Normalizer.normalize(orEmpty(s), Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]+", "-")
        .replaceAll("^-+|-+$", "");
    if (slug.length() > maxLength) {
      slug = slug.substring(0, maxLength).replaceAll("-+$", "");
    }
    return slug.isEmpty() ? "unknown" : slug;
  }

  private static String folderName(String jobSite) {
    if (jobSite == null || jobSite.isBlank()) {
      return "unknown";
    }
    return jobSite.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
  }

  private static String shortHash(String url) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(orEmpty(url).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest).substring(0, 6);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }

  // Callers get copies, so changes they make (like search highlighting) don't leak into the stored jobs.
  private static Job copy(Job job) {
    Job copy = new Job();
    copy.id = job.id;
    copy.title = job.title;
    copy.url = job.url;
    copy.company = job.company;
    copy.description = job.description;
    copy.descriptionText = job.descriptionText;
    copy.status = job.status;
    copy.job_site = job.job_site;
    copy.job_posting_date = job.job_posting_date;
    return copy;
  }
}
