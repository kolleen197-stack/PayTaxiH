package com.paytaxihub.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
public class AuthFilter extends OncePerRequestFilter {
    private final AuthSessionService sessions;
    public AuthFilter(AuthSessionService sessions){this.sessions=sessions;}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
        String path=req.getRequestURI();
        res.setHeader("X-Content-Type-Options","nosniff");
        res.setHeader("Referrer-Policy","strict-origin-when-cross-origin");
        res.setHeader("X-Frame-Options","DENY");
        if(path.startsWith("/api/")||path.endsWith(".html")){
            res.setHeader("Cache-Control","no-store, no-cache, must-revalidate, max-age=0");
            res.setHeader("Pragma","no-cache");
            res.setHeader("Expires","0");
        }
        if(!path.startsWith("/api/") || path.equals("/api/health") || path.equals("/api/auth/login") || path.equals("/api/auth/register") || path.equals("/api/internal/bootstrap-admin") || path.equals("/api/auth/forgot-password") || path.equals("/api/auth/reset-password") || path.equals("/api/auth/mfa/verify") || "OPTIONS".equalsIgnoreCase(req.getMethod())){chain.doFilter(req,res);return;}
        String h=req.getHeader("Authorization");
        String token=h!=null&&h.startsWith("Bearer ")?h.substring(7):null;
        if(token==null){
            jakarta.servlet.http.Cookie[] cookies=req.getCookies();
            if(cookies!=null){
                for(jakarta.servlet.http.Cookie c:cookies){
                    if("PTH_SESSION".equals(c.getName())){ token=c.getValue(); break; }
                }
            }
        }
        AuthSessionService.Session s=sessions.validate(token);
        if(s==null){res.setStatus(401);res.setContentType(MediaType.APPLICATION_JSON_VALUE);res.getWriter().write("{\"message\":\"Authentication required or session expired.\"}");return;}
        req.setAttribute("authUserId",s.userId()); req.setAttribute("authRole",s.role()); req.setAttribute("authToken",token);
        chain.doFilter(req,res);
    }
}
