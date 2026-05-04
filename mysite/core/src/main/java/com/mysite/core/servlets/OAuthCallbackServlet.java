package com.mysite.core.servlets;

import java.io.IOException;

import javax.servlet.Servlet;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Component;

@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/callback",
                "sling.servlet.methods=GET",
                "sling.servlet.extensions=txt"

        }
)
public class OAuthCallbackServlet extends SlingSafeMethodsServlet {

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws IOException {

        String code = request.getParameter("code");
        String error = request.getParameter("error");

        System.out.println("🔥 OAuth Callback Hit 🔥");

        if (error != null) {
            System.out.println("Error: " + error);
            response.getWriter().write("Error during OAuth: " + error);
            return;
        }

        if (code != null) {
            System.out.println("Authorization Code: " + code);

            response.getWriter().write(
                    "✅ OAuth Success!<br><br>" +
                            "Authorization Code:<br>" + code
            );
        } else {
            response.getWriter().write("No code received.");
        }
    }
}