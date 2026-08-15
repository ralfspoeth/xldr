package io.github.ralfspoeth.xldr.xlet;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public class XldrServlet extends HttpServlet {

    @Override
    public void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        var mimeType = req.getContentType();
        var semiColon = mimeType.indexOf(';');
        if (semiColon != -1) {
            mimeType = mimeType.substring(0, semiColon);
        }
        var spec = req.getParameter("spec");
        try(var is = req.getInputStream()) {

        }
    }
}
