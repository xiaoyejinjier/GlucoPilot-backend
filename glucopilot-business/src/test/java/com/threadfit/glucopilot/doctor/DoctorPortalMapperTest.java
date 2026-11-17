package com.threadfit.glucopilot.doctor;

import static org.junit.jupiter.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.threadfit.glucopilot.doctor.mapper.DoctorPortalMapper;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

class DoctorPortalMapperTest {
    JdbcTemplate jdbc;
    SqlSession session;
    DoctorPortalMapper mapper;

    @BeforeEach
    void setup() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:doctor_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                "CREATE TABLE user_account(id BIGINT PRIMARY KEY,is_deleted INT DEFAULT"
                        + " 0,account_status INT DEFAULT 1)");
        jdbc.execute(
                "CREATE TABLE consultation_doctor(user_id BIGINT PRIMARY KEY,real_name"
                        + " VARCHAR(50),hospital VARCHAR(120),specialty VARCHAR(80),status"
                        + " VARCHAR(16))");
        jdbc.execute(
                "CREATE TABLE doctor_ops_binding(doctor_user_id BIGINT PRIMARY KEY,ops_id BIGINT"
                        + " UNIQUE,created_by BIGINT)");
        jdbc.execute(
                "CREATE TABLE gp_ops_identity_map(legacy_ops_id BIGINT PRIMARY KEY,ruoyi_user_id"
                        + " BIGINT UNIQUE)");
        jdbc.execute(
                "CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,user_name VARCHAR(64),status"
                        + " CHAR(1),del_flag CHAR(1))");
        jdbc.execute(
                "CREATE TABLE consultation_wallet_entry(id BIGINT PRIMARY KEY,order_no"
                        + " VARCHAR(64),user_id BIGINT,amount_cents INT,balance_after_cents"
                        + " BIGINT,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute(
                "CREATE TABLE consultation_order(order_no VARCHAR(64) PRIMARY KEY,consultation_id"
                        + " BIGINT,doctor_user_id BIGINT,status VARCHAR(16))");
        jdbc.update("INSERT INTO user_account(id) VALUES(20),(21),(22)");
        jdbc.update(
                "INSERT INTO consultation_doctor VALUES(20,'Doctor"
                    + " A','Hospital','Specialty','APPROVED'),(21,'Doctor"
                    + " B','Hospital','Specialty','APPROVED'),(22,'Pending','Hospital','Specialty','PENDING')");
        jdbc.update("INSERT INTO gp_ops_identity_map VALUES(7,70),(8,80)");
        jdbc.update("INSERT INTO sys_user VALUES(70,'doctor_a','0','0'),(80,'doctor_b','0','0')");
        jdbc.update("INSERT INTO doctor_ops_binding VALUES(20,7,1),(21,8,1)");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("test", new JdbcTransactionFactory(), ds));
        config.addMapper(DoctorPortalMapper.class);
        session = new MybatisSqlSessionFactoryBuilder().build(config).openSession(true);
        mapper = session.getMapper(DoctorPortalMapper.class);
    }

    @AfterEach
    void close() {
        if (session != null) session.close();
    }

    @Test
    void bindingUsesBothIdentitiesAndRejectsDisabledAccountOrQualification() {
        assertEquals(20L, mapper.boundDoctor(7));
        assertEquals(21L, mapper.boundDoctor(8));
        assertNull(mapper.boundDoctor(9));
        jdbc.update("UPDATE consultation_doctor SET status='DISABLED' WHERE user_id=20");
        session.clearCache();
        assertNull(mapper.boundDoctor(7));
        assertTrue(mapper.bindingExists(7));
        jdbc.update("UPDATE sys_user SET status='1' WHERE user_id=80");
        session.clearCache();
        assertNull(mapper.boundDoctor(8));
    }

    @Test
    void accountsUseActualRuoyiColumnNamesAndPaginateOnlyApprovedOrBoundDoctors() {
        assertEquals(2, mapper.accountCount());
        var page = mapper.accounts(1, 0);
        assertEquals(1, page.size());
        assertEquals("doctor_b", page.getFirst().username());
        assertEquals(8L, page.getFirst().opsId());
        assertEquals(1, page.getFirst().accountStatus());
        assertEquals(20L, mapper.accounts(1, 1).getFirst().doctorUserId());
    }

    @Test
    void incomeShowsOnlyOwnPaidPositiveTransfers() {
        jdbc.update(
                "INSERT INTO consultation_order"
                        + " VALUES('a',1,20,'PAID'),('b',2,21,'PAID'),('c',3,20,'PENDING')");
        jdbc.update(
                "INSERT INTO"
                    + " consultation_wallet_entry(id,order_no,user_id,amount_cents,balance_after_cents)"
                    + " VALUES(1,'a',20,100,100),(2,'a',1,-100,0),(3,'b',21,100,100),(4,'c',20,100,200)");
        assertEquals(1, mapper.incomeCount(20));
        assertEquals("a", mapper.income(20, 10, 0).getFirst().orderNo());
        assertEquals(100, mapper.income(20, 10, 0).getFirst().amountCents());
        assertEquals("b", mapper.income(21, 10, 0).getFirst().orderNo());
    }
}
