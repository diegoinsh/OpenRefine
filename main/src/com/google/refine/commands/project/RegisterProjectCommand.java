package com.google.refine.commands.project;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.refine.ProjectManager;
import com.google.refine.commands.Command;

/**
 * 运行期把工作区中已存在的项目目录重新登记进内存。
 *
 * <p>OR 只在启动时扫描工作区，从回收站把 {@code {id}.project} 移回后，
 * 需要这个命令让项目立即回到列表，避免必须重启引擎。
 */
public class RegisterProjectCommand extends Command {

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        if (!hasValidCSRFToken(request)) {
            respondCSRFError(response);
            return;
        }

        response.setHeader("Content-Type", "application/json");
        try {
            long projectID = Long.parseLong(request.getParameter("project"));
            boolean registered = ProjectManager.singleton.registerExistingProject(projectID);
            respond(response, registered
                    ? "{\"code\":\"ok\",\"registered\":true}"
                    : "{\"code\":\"error\",\"message\":\"工作区中不存在该项目目录，或元数据不可读\"}");
        } catch (Exception e) {
            respondException(response, e);
        }
    }

}
