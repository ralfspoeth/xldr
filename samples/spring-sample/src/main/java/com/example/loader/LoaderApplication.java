package com.example.loader;

import io.github.ralfspoeth.xldr.xlet.XldrServlet;
import jakarta.servlet.ServletException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.lang.NonNull;

import javax.sql.DataSource;

/**
 * xlet under Spring Boot, packaged as a war.
 *
 * <p>Two things differ from the Jetty sample beside it, and both are the point
 * of having this one.
 *
 * <p><strong>The {@code DataSource} comes from Spring, not from JNDI.</strong>
 * {@code XldrServlet.dataSource()} is {@code protected} precisely so that a
 * deployment which already has one can say so - its javadoc names Spring as the
 * case - and overriding it means the application needs no JNDI at all, which
 * under an embedded container it would otherwise have to switch on.
 *
 * <p><strong>The servlet is registered in Java rather than in
 * {@code web.xml}.</strong> A {@link ServletRegistrationBean} gives the same
 * mapping, the same init-params and the same {@code load-on-startup}, in the
 * place a Spring application keeps such things. There is still a
 * {@code /WEB-INF/specs/} directory, because that is where the servlet reads
 * specs from however it was registered.
 */
@SpringBootApplication
public class LoaderApplication extends SpringBootServletInitializer {

    static void main(String[] args) {
        SpringApplication.run(LoaderApplication.class, args);
    }

    /** so the same war deploys to a standalone container as well as running itself */
    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(LoaderApplication.class);
    }

    /**
     * The servlet, its mapping and its parameters.
     * <p>
     * {@code loadOnStartup(1)} matters as much here as the {@code
     * <load-on-startup>} it stands for: xlet reads the specs and settles
     * everything in {@code init()}, so a mistake is a failure to start rather
     * than a 500 on somebody's first request.
     */
    @Bean
    ServletRegistrationBean<SpringDataSourceServlet> xldr(DataSource dataSource) {
        var registration = new ServletRegistrationBean<>(new SpringDataSourceServlet(dataSource), "/load");
        registration.setName("xldr");
        // what ${env.source} in the spec resolves to, as a feed's
        // env.properties would supply it under the file server
        registration.addInitParameter("env.source", "spring");
        registration.setLoadOnStartup(1);
        return registration;
    }

    /**
     * The whole of the integration: a servlet that already knows its database.
     * <p>
     * Everything else - reading the specs, choosing the adapter, the transaction,
     * the concurrency limit - is the servlet's own and unchanged.
     */
    static final class SpringDataSourceServlet extends XldrServlet {

        private final transient DataSource dataSource;

        SpringDataSourceServlet(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        @NonNull
        protected DataSource dataSource() throws ServletException {
            return dataSource;
        }
    }
}
