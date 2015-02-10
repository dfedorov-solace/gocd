/*************************GO-LICENSE-START*********************************
 * Copyright 2014 ThoughtWorks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *************************GO-LICENSE-END***********************************/

package com.thoughtworks.go.server;

import com.thoughtworks.go.server.util.GoCipherSuite;
import com.thoughtworks.go.server.util.GoSslSocketConnector;
import com.thoughtworks.go.util.GoConstants;
import com.thoughtworks.go.util.SubprocessLogger;
import com.thoughtworks.go.util.SystemEnvironment;
import com.thoughtworks.go.util.validators.*;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOCase;
import org.apache.commons.io.filefilter.FalseFileFilter;
import org.apache.commons.io.filefilter.SuffixFileFilter;
import org.apache.log4j.Logger;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.*;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.webapp.JettyWebXmlConfiguration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebInfConfiguration;
import org.eclipse.jetty.webapp.WebXmlConfiguration;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.xml.sax.SAXException;

import javax.management.MBeanServer;
import javax.net.ssl.SSLSocketFactory;
import javax.servlet.ServletException;
import javax.servlet.UnavailableException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

public class GoServer {

    private static final Logger LOG = Logger.getLogger(GoServer.class);
    public static final String GO_FORCE_LOAD_PAGE_HEADER = "X-GO-FORCE-LOAD-PAGE";

    private SystemEnvironment systemEnvironment;
    private final String password = "serverKeystorepa55w0rd";
    private JettyServer server;
    protected SubprocessLogger subprocessLogger;
    private GoCipherSuite goCipherSuite;
    private GoWebXmlConfiguration configuration;

    public GoServer() {
        this(new SystemEnvironment(), new GoCipherSuite((SSLSocketFactory) SSLSocketFactory.getDefault()), new GoWebXmlConfiguration());
    }

    protected GoServer(SystemEnvironment systemEnvironment, GoCipherSuite goCipherSuite, GoWebXmlConfiguration goWebXmlConfiguration) {
        this.systemEnvironment = systemEnvironment;
        subprocessLogger = new SubprocessLogger();
        this.goCipherSuite = goCipherSuite;
        this.configuration = goWebXmlConfiguration;
    }

    public void go() throws Exception {
        Validation validation = validate();
        if (validation.isSuccessful()) {
            subprocessLogger.registerAsExitHook("Following processes were alive at shutdown: ");
            startServer();
        } else {
            validation.logErrors();
        }
    }

    protected void startServer() throws Exception {
        server = configureServer();
        server.start();

        Throwable exceptionAtServerStart = server.webAppContext().getUnavailableException();
        if (exceptionAtServerStart != null) {
            server.stop();
            LOG.error("ERROR: Failed to start Go server.", exceptionAtServerStart);
            throw new RuntimeException("Failed to start Go server.", exceptionAtServerStart);
        }
    }

    JettyServer configureServer() throws Exception {
        JettyServer server = createServer();

        server.getContainer().addEventListener(mbeans());

        server.addConnector(plainConnector(server));
        server.addConnector(sslConnector(server));

        HandlerCollection handlers = new HandlerCollection();

        handlers.addHandler(welcomeFileHandler());
        handlers.addHandler(legacyRequestHandler());
        WebAppContext webAppContext = webApp();
        addResourceHandler(handlers, webAppContext);
        handlers.addHandler(webAppContext);
        server.setWebAppContext(webAppContext);

        server.setHandler(handlers);
        performCustomConfiguration(server);

        server.setStopAtShutdown(true);
        return server;
    }
    private void addResourceHandler(HandlerCollection handlers, WebAppContext webAppContext) throws IOException {
        if (!systemEnvironment.useCompressedJs()) return;
        AssetsContextHandler handler = new AssetsContextHandler(systemEnvironment);
        handlers.addHandler(handler);
        webAppContext.addLifeCycleListener(new AssetsContextHandlerInitializer(handler, webAppContext));
    }

    JettyServer createServer() {
        return new JettyServer(new Server());
    }

    private void performCustomConfiguration(JettyServer server) throws Exception {
        File jettyConfig = systemEnvironment.getJettyConfigFile();
        if (jettyConfig.exists()) {
            LOG.info("Configuring Jetty using " + jettyConfig.getAbsolutePath());
            FileInputStream serverConfiguration = new FileInputStream(jettyConfig);
            XmlConfiguration configuration = new XmlConfiguration(serverConfiguration);
            configuration.configure(server.getServer());
        } else {
            String message = String.format(
                    "No custom jetty configuration (%s) found, using defaults.",
                    jettyConfig.getAbsolutePath());
            LOG.info(message);
        }
    }

    private MBeanContainer mbeans() {
        MBeanServer platformMBeanServer = ManagementFactory.getPlatformMBeanServer();
        MBeanContainer mbeans = new MBeanContainer(platformMBeanServer);
        // mbeans.start();
        return mbeans;
    }

	private Connector plainConnector(JettyServer server) {
		return new GoSslSocketConnector(password, systemEnvironment, goCipherSuite).plainConnector(server.getServer());
	}

	private Connector sslConnector(JettyServer server) {
		return new GoSslSocketConnector(password, systemEnvironment, goCipherSuite).sslConnector(server.getServer());
	}

    WebAppContext webApp() throws IOException, SAXException, ClassNotFoundException, UnavailableException {
        WebAppContext wac = new WebAppContext();
		wac.setDefaultsDescriptor(GoWebXmlConfiguration.configuration(getWarFile()));

        wac.setConfigurationClasses(new String[]{
				WebInfConfiguration.class.getCanonicalName(),
				WebXmlConfiguration.class.getCanonicalName(),
				JettyWebXmlConfiguration.class.getCanonicalName()
        });
        wac.setContextPath(new SystemEnvironment().getWebappContextPath());
        wac.setWar(getWarFile());
        wac.setParentLoaderPriority(new SystemEnvironment().getParentLoaderPriority());
        setCookieExpireIn2Weeks(wac);
        addExtraJarsToClasspath(wac);
        addJRubyContextInitParams(wac);
        addStopServlet(wac);
        return wac;
    }

    private void addJRubyContextInitParams(WebAppContext wac) {
        wac.setInitParameter("rails.root", "/WEB-INF/rails.new");
    }

    private void addExtraJarsToClasspath(WebAppContext wac) {
        ArrayList<File> extraClassPathFiles = new ArrayList<File>();
        extraClassPathFiles.addAll(getAddonJarFiles());
        String extraClasspath = convertToClasspath(extraClassPathFiles);
        LOG.info("Including addons: " + extraClasspath);
        wac.setExtraClasspath(extraClasspath);
    }

    ContextHandler welcomeFileHandler() {
        return new GoServerWelcomeFileHandler();
    }

    Handler legacyRequestHandler() {
        return new LegacyUrlRequestHandler();
    }


    private void setCookieExpireIn2Weeks(WebAppContext wac) {
        int sixMonths = 60 * 60 * 24 * 14;
        wac.getSessionHandler().getSessionManager().getSessionCookieConfig().setMaxAge(sixMonths);
    }

    private void addStopServlet(WebAppContext wac) {
        ServletHolder holder = new ServletHolder();
        holder.setServlet(new StopJettyFromLocalhostServlet(this));
        wac.addServlet(holder, "/jetty/stop");
    }

    private String getWarFile() {
        return systemEnvironment.getCruiseWar();
    }

    public void stop() throws Exception {
        server.stop();
    }

    Validation validate() {
        Validation validation = new Validation();
        for (Validator validator : validators()) {
            validator.validate(validation);
        }
        return validation;
    }

    private List<File> getAddonJarFiles() {
        File addonsPath = new File(systemEnvironment.get(SystemEnvironment.ADDONS_PATH));
        if (!addonsPath.exists() || !addonsPath.canRead()) {
            return new ArrayList<File>();
        }

        return new ArrayList<File>(FileUtils.listFiles(addonsPath, new SuffixFileFilter("jar", IOCase.INSENSITIVE), FalseFileFilter.INSTANCE));
    }

    private String convertToClasspath(List<File> addonJars) {
        if (addonJars.size() == 0) {
            return "";
        }

        StringBuilder addonJarClassPath = new StringBuilder(addonJars.get(0).getPath());
        for (int i = 1; i < addonJars.size(); i++) {
            addonJarClassPath.append(",").append(addonJars.get(i));
        }
        return addonJarClassPath.toString();
    }

    ArrayList<Validator> validators() {
        ArrayList<Validator> validators = new ArrayList<Validator>();
        validators.add(new ServerPortValidator(systemEnvironment.getServerPort()));
        validators.add(new ServerPortValidator(systemEnvironment.getSslServerPort()));
        validators.add(new ServerPortValidator(systemEnvironment.getDatabaseSeverPort()));
        validators.add(FileValidator.defaultFile("agent-bootstrapper.jar"));
        validators.add(FileValidator.defaultFile("agent.jar"));
        validators.add(FileValidator.defaultFile("agent-launcher.jar"));
        validators.add(FileValidator.defaultFile("cruise.war"));
        validators.add(FileValidator.defaultFile("historical_jars/h2-1.2.127.jar"));
        validators.add(FileValidator.configFile("cruise-config.xml", systemEnvironment));
        validators.add(FileValidator.configFile("config.properties", systemEnvironment));
        validators.add(FileValidator.configFileAlwaysOverwrite("cruise-config.xsd", systemEnvironment));
		validators.add(FileValidator.configFileAlwaysOverwrite("jetty.xml", systemEnvironment));
        validators.add(new JettyWorkDirValidator());
        validators.add(new DatabaseValidator());
        validators.add(new LoggingValidator(systemEnvironment));
        return validators;
    }

    static class LegacyUrlRequestHandler extends HandlerWrapper {

        private final String oldContextUrl = GoConstants.OLD_URL_CONTEXT;
        private final String newContextUrl = GoConstants.GO_URL_CONTEXT;
        private final MovedContextHandler movedContextHandler;

        private LegacyUrlRequestHandler() {
            this(new MovedContextHandler());
        }

        LegacyUrlRequestHandler(MovedContextHandler movedContextHandler) {
            this.movedContextHandler = movedContextHandler;
            movedContextHandler.setContextPath(oldContextUrl);
            movedContextHandler.setNewContextURL(newContextUrl);
            movedContextHandler.setPermanent(true);
            setHandler(movedContextHandler);
        }

        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            if (target.startsWith(GoConstants.OLD_URL_CONTEXT + "/") || target.equals(GoConstants.OLD_URL_CONTEXT)) {
                String content = String.format("Url(s) starting in '%s' have been permanently moved to '%s', please use the new path.", oldContextUrl, newContextUrl);
                response.setHeader("Content-Type", "text/plain");
                movedContextHandler.handle(target, baseRequest, request, response);
                response.setHeader("Content-Length", String.valueOf(content.length()));
                PrintWriter writer = response.getWriter();
                writer.write(content);
                writer.close();
            }
        }
    }

    class GoServerWelcomeFileHandler extends ContextHandler {
        public GoServerWelcomeFileHandler() {
            setContextPath("/");
            setHandler(new Handler());
        }

        private class Handler extends AbstractHandler {
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
                if (target.equals("/")) {
                    response.setHeader("Location", GoConstants.GO_URL_CONTEXT + "/home");
                    response.setStatus(301);
                    response.setHeader("Content-Type", "text/html");
                    PrintWriter writer = response.getWriter();
                    writer.write("redirecting..");
                    writer.close();
                }
            }
        }
    }
}
