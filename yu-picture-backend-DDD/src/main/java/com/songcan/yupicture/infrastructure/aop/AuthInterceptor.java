package com.songcan.yupicture.infrastructure.aop;

import com.songcan.yupicture.infrastructure.annotation.AuthCheck;
import com.songcan.yupicture.infrastructure.exception.BusinessException;
import com.songcan.yupicture.infrastructure.exception.ErrorCode;
import com.songcan.yupicture.domain.user.entity.User;
import com.songcan.yupicture.domain.user.valueobject.UserRoleEnum;
import com.songcan.yupicture.application.service.UserApplicationService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

@Aspect
@Configuration
public class AuthInterceptor {

    @Resource
    public UserApplicationService userApplicationService;

    /**
     * 执行拦截
     * @param joinPoint 切点
     * @param authCheck 权限校验注解
     * @return
     */
    @Around("@annotation(authCheck)")
    public Object doIntercept(ProceedingJoinPoint joinPoint, AuthCheck authCheck) throws Throwable {
        String mustRole = authCheck.mustRole();
        RequestAttributes requestAttributes = RequestContextHolder.currentRequestAttributes();
        HttpServletRequest request = ((ServletRequestAttributes) requestAttributes).getRequest();
        // 获取当前登录用户
        User loginUser = userApplicationService.getLoginUser(request);
        UserRoleEnum mustRoleEnum = UserRoleEnum.getEnumByValue(mustRole);
        //如果不需要权限放行
        if(mustRoleEnum == null){
            return joinPoint.proceed();
        }
        //以下的代码,必须有权限才能通过
        UserRoleEnum userRoleEnum = UserRoleEnum.getEnumByValue(loginUser.getUserRole());
        if(userRoleEnum == null){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        //必须要有管理员权限,但是用户没有管理员权限,报错
        if(UserRoleEnum.ADMIN.equals(mustRoleEnum) && !UserRoleEnum.ADMIN.equals(userRoleEnum)){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        //用户有权限,放行
        return joinPoint.proceed();
    }
}
