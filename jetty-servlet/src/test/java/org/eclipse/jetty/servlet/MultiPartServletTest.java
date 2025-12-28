//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.servlet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.client.util.MultiPartContentProvider;
import org.eclipse.jetty.client.util.OutputStreamContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.MultiPartFormInputStream;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.MultiPartFormDataCompliance;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MultiPartServletTest
{
    private static final Logger LOG = Log.getLogger(MultiPartServletTest.class);

    private Server server;
    private ServerConnector connector;
    private HttpClient client;
    private Path tmpDir;

    private static final int MAX_FILE_SIZE = 512 * 1024;
    private static final int MAX_REQUEST_SIZE = 1024 * 1024 * 8;
    private static final int LARGE_MESSAGE_SIZE = 1024 * 1024;

    public static Stream<Arguments> data()
    {
        return Arrays.asList(MultiPartFormDataCompliance.values()).stream().map(Arguments::of);
    }

    public static class RequestParameterServlet extends HttpServlet
    {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            req.getParameterMap();
            req.getParts();
            resp.setStatus(200);
            resp.getWriter().print("success");
            resp.getWriter().close();
        }
    }

    public static class MultiPartServlet extends HttpServlet
    {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            if (!req.getContentType().contains(MimeTypes.Type.MULTIPART_FORM_DATA.asString()))
            {
                resp.setContentType("text/plain");
                resp.getWriter().println("not content type " + MimeTypes.Type.MULTIPART_FORM_DATA);
                resp.getWriter().println("contentType: " + req.getContentType());
                return;
            }

            resp.setContentType("text/plain");
            for (Part part : req.getParts())
            {
                resp.getWriter().println("Part: name=" + part.getName() + ", size=" + part.getSize());
            }
        }
    }

    @BeforeEach
    public void start() throws Exception
    {
        tmpDir = Files.createTempDirectory(MultiPartServletTest.class.getSimpleName());
        Files.deleteIfExists(tmpDir);

        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        MultipartConfigElement config = new MultipartConfigElement(tmpDir.toAbsolutePath().toString(),
            MAX_FILE_SIZE, -1, 1);
        MultipartConfigElement requestSizedConfig = new MultipartConfigElement(tmpDir.toAbsolutePath().toString(),
            -1, MAX_REQUEST_SIZE, 1);
        MultipartConfigElement defaultConfig = new MultipartConfigElement(tmpDir.toAbsolutePath().toString(),
            -1, -1, 1);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        ServletHolder servletHolder = contextHandler.addServlet(MultiPartServlet.class, "/");
        servletHolder.getRegistration().setMultipartConfig(config);
        servletHolder = contextHandler.addServlet(RequestParameterServlet.class, "/defaultConfig");
        servletHolder.getRegistration().setMultipartConfig(defaultConfig);
        servletHolder = contextHandler.addServlet(RequestParameterServlet.class, "/requestSizeLimit");
        servletHolder.getRegistration().setMultipartConfig(requestSizedConfig);

        server.setHandler(contextHandler);

        server.start();

        client = new HttpClient();
        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        client.stop();
        server.stop();

        IO.delete(tmpDir.toFile());
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testLargePart(MultiPartFormDataCompliance compliance) throws Exception
    {
        connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration()
            .setMultiPartFormDataCompliance(compliance);

        OutputStreamContentProvider content = new OutputStreamContentProvider();
        MultiPartContentProvider multiPart = new MultiPartContentProvider();
        multiPart.addFieldPart("param", content, null);
        multiPart.close();

        InputStreamResponseListener listener = new InputStreamResponseListener();
        client.newRequest("localhost", connector.getLocalPort())
            .path("/defaultConfig")
            .scheme(HttpScheme.HTTP.asString())
            .method(HttpMethod.POST)
            .content(multiPart)
            .send(listener);

        // Write large amount of content to the part.
        byte[] byteArray = new byte[1024 * 1024];
        Arrays.fill(byteArray, (byte)1);
        for (int i = 0; i < 128 * 2; i++)
        {
            content.getOutputStream().write(byteArray);
        }
        content.close();

        Response response = listener.get(2, TimeUnit.MINUTES);
        assertThat(response.getStatus(), equalTo(HttpStatus.BAD_REQUEST_400));
        String responseContent = IO.toString(listener.getInputStream());
        assertThat(responseContent, containsString("Unable to parse form content"));
        assertThat(responseContent, containsString("Form is larger than max length"));
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testManyParts(MultiPartFormDataCompliance compliance) throws Exception
    {
        connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration()
            .setMultiPartFormDataCompliance(compliance);

        byte[] byteArray = new byte[1024];
        Arrays.fill(byteArray, (byte)1);

        MultiPartContentProvider multiPart = new MultiPartContentProvider();
        for (int i = 0; i < 1024 * 1024; i++)
        {
            BytesContentProvider content = new BytesContentProvider(byteArray);
            multiPart.addFieldPart("part" + i, content, null);
        }
        multiPart.close();

        InputStreamResponseListener listener = new InputStreamResponseListener();
        client.newRequest("localhost", connector.getLocalPort())
            .path("/defaultConfig")
            .scheme(HttpScheme.HTTP.asString())
            .method(HttpMethod.POST)
            .content(multiPart)
            .send(listener);

        Response response = listener.get(30, TimeUnit.SECONDS);
        assertThat(response.getStatus(), equalTo(HttpStatus.BAD_REQUEST_400));
        String responseContent = IO.toString(listener.getInputStream());
        assertThat(responseContent, containsString("Unable to parse form content"));
        assertThat(responseContent, containsString("Form with too many parts"));
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testMaxRequestSize(MultiPartFormDataCompliance compliance) throws Exception
    {
        connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration()
            .setMultiPartFormDataCompliance(compliance);

        OutputStreamContentProvider content = new OutputStreamContentProvider();
        MultiPartContentProvider multiPart = new MultiPartContentProvider();
        multiPart.addFieldPart("param", content, null);
        multiPart.close();

        InputStreamResponseListener listener = new InputStreamResponseListener();
        client.newRequest("localhost", connector.getLocalPort())
            .path("/requestSizeLimit")
            .scheme(HttpScheme.HTTP.asString())
            .method(HttpMethod.POST)
            .content(multiPart)
            .send(listener);

        Throwable writeError = null;
        try
        {
            // Write large amount of content to the part.
            byte[] byteArray = new byte[1024 * 1024];
            Arrays.fill(byteArray, (byte)1);
            for (int i = 0; i < 512; i++)
            {
                content.getOutputStream().write(byteArray);
            }
        }
        catch (Exception e)
        {
            writeError = e;
        }

        if (writeError != null)
            assertThat(writeError, instanceOf(EofException.class));

        // We should get 400 response.
        Response response = listener.get(30, TimeUnit.SECONDS);
        assertThat(response.getStatus(), equalTo(HttpStatus.BAD_REQUEST_400));
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testTempFilesDeletedOnError(MultiPartFormDataCompliance compliance) throws Exception
    {
        connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration()
            .setMultiPartFormDataCompliance(compliance);

        byte[] byteArray = new byte[LARGE_MESSAGE_SIZE];
        for (int i = 0; i < byteArray.length; i++)
        {
            byteArray[i] = 1;
        }
        BytesContentProvider contentProvider = new BytesContentProvider(byteArray);

        MultiPartContentProvider multiPart = new MultiPartContentProvider();
        multiPart.addFieldPart("largePart", contentProvider, null);
        multiPart.close();

        try (StacklessLogging stacklessLogging = new StacklessLogging(HttpChannel.class, MultiPartFormInputStream.class))
        {
            ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(HttpScheme.HTTP.asString())
                .method(HttpMethod.POST)
                .content(multiPart)
                .send();

            assertEquals(500, response.getStatus());
            assertThat(response.getContentAsString(),
                containsString("Multipart Mime part largePart exceeds max filesize"));
        }

        assertThat(tmpDir.toFile().list().length, is(0));
    }
}
