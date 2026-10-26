package com.threadfit.glucopilot.community;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.context.RequestContext;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class AdminCommunityTagHttpTest {
 @Configuration @EnableMethodSecurity static class Config {
  @Bean CommunityTagService tags(){return mock(CommunityTagService.class);}
  @Bean AdminCommunityTagController controller(CommunityTagService tags){return new AdminCommunityTagController(tags);}
  @Bean(name="ss") Gate gate(){return new Gate();}
 }
 public static class Gate {
  public boolean hasPermi(String permission){var auth=SecurityContextHolder.getContext().getAuthentication();return auth!=null&&auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals(permission));}
  public boolean hasRole(String role){return hasPermi("ROLE_"+role);}
 }
 @Test void readPermissionCannotWriteAndAdministratorNeedsBothRoleAndPermission() throws Exception {
  try(var context=new AnnotationConfigApplicationContext(Config.class)){
   var controller=context.getBean(AdminCommunityTagController.class);var service=context.getBean(CommunityTagService.class);
   var change=new CommunityTagService.Change("新分类",4,1);
   SecurityContextHolder.clearContext();assertThrows(AccessDeniedException.class,()->controller.remove("diet"));
   RequestContext.setOpsId(99L);SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("editor","unused","content:news:list","content:news:edit"));
   assertThrows(AccessDeniedException.class,()->controller.create(change));assertThrows(AccessDeniedException.class,()->controller.update("diet",change));assertThrows(AccessDeniedException.class,()->controller.remove("diet"));
   when(service.list(any(),any(),any(),any())).thenReturn(PageResult.of(1,10,0,List.of()));assertEquals(0,controller.list("","",null,new PageQuery()).getCode());
   SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("admin","unused","ROLE_admin","content:news:edit","content:news:list"));
   var row=new CommunityTagService.Row("7","topic_new","新分类","OFFICIAL",4,1,0);when(service.create(change)).thenReturn(row);when(service.update("diet",change)).thenReturn(row);
   var mvc=MockMvcBuilders.standaloneSetup(controller).build();
   var response=mvc.perform(post("/admin/v1/community/tags").contentType("application/json").content("{\"name\":\"新分类\",\"sortNo\":4,\"enabled\":1}")).andReturn().getResponse();assertEquals(200,response.getStatus());verify(service).create(change);
   assertEquals(200,mvc.perform(put("/admin/v1/community/tags/diet").contentType("application/json").content("{\"name\":\"新分类\",\"sortNo\":4,\"enabled\":1}")).andReturn().getResponse().getStatus());verify(service).update("diet",change);
   assertEquals(200,mvc.perform(delete("/admin/v1/community/tags/diet")).andReturn().getResponse().getStatus());verify(service).remove("diet");
  }finally{RequestContext.clear();SecurityContextHolder.clearContext();}
 }
}
