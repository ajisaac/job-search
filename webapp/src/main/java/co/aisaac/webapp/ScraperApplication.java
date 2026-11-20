package co.aisaac.webapp;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.rendering.template.JavalinThymeleaf;

import java.nio.file.Path;

public class ScraperApplication {

    public static void main(String[] args) {
        Jobs jobRepo = new Jobs();

        WebController web = new WebController(jobRepo);
        TitlesController titles = new TitlesController(jobRepo);
        CaptureController capture = new CaptureController(jobRepo, Path.of("pages"));

        Javalin.create(config -> {
            config.staticFiles.add("/static", Location.CLASSPATH);
            config.fileRenderer(new JavalinThymeleaf());
            config.jetty.port = 8080;
            // whole pages from the browser extension; LinkedIn's are several MB
            config.http.maxRequestSize = 50_000_000L;

            // main page
            config.routes.get("/", web::all);
            config.routes.get("/job/{site}/{name}", web::detail);
            config.routes.post("/status/{site}/{name}/{status}", web::updateStatus);

            // titles page
            config.routes.get("/titles", titles::all);

            // browser extension
            config.routes.post("/api/pages", capture::capture);
        }).start();
    }
}
