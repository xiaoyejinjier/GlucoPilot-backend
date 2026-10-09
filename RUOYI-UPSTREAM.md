# Official upstream

Repository: https://github.com/yangzongzhuan/RuoYi-Vue

Commit: 13db1fcef36bee9ce45d2d636a1d4e8f5ed5bbc3

Version: 3.9.2 (Spring Boot 4). License: RUOYI-LICENSE.

Local integration changes: Boot 4.1.1 / Java 21, JJWT 0.13 split artifacts, explicit security and MyBatis factories, custom business/API modules. Official standalone configurations are profile-gated; official system, permission, authentication, audit, Quartz and generator services are used by the compatibility API.

The compatibility layer keeps GlucoPilot's HTTP paths and response envelope. Super administrator checks use the active `super_admin` role rather than user/role ID 1. Redis keys have an application prefix. Password and username limits retain the existing API range; HS512 keys are validated at startup. Generator settings bind under `gen.*`. Config/dictionary/job initialization waits for Flyway. XML inserts return generated IDs for compatibility responses, user updates permit renaming, and root department creation/movement is supported. Quartz invocation is restricted to three fixed GlucoPilot task methods. Upstream standalone Security, datasource, MVC and mapper configurations are retained under an inactive profile because adapter configurations own these beans.

Historical GlucoPilot system tables are archived by Flyway V10. Grouped application configuration uses `gp_app_config`; it is separate from the official system parameter service. The task scheduler uses upstream's default RAM store with database-backed task definitions and startup restoration; run a single scheduler instance.
