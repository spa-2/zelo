package com.server.zelo.domain.order.entity;

import jakarta.persistence.Entity;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.relational.internal.SqlStringGenerationContextImpl;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.tool.schema.extract.spi.TableInformation;
import org.hibernate.tool.schema.internal.SchemaCreatorImpl;
import org.hibernate.tool.schema.internal.StandardTableMigrator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 엔티티 매핑으로 PostgreSQL DDL을 생성한다. DB 접속이나 기존 행의 보정은 수행하지 않는다.
@ExtendWith(MockitoExtension.class)
class OrderItemSchemaDefaultTest {

    private static StandardServiceRegistry registry;
    private static Metadata metadata;

    @Mock
    private TableInformation existingTable;

    @BeforeAll
    static void setUpMetadata() throws ClassNotFoundException {
        registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", PostgreSQLDialect.class.getName())
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                .build();
        MetadataSources sources = new MetadataSources(registry);
        // 연관 엔티티도 등록하여 OrderItem의 실제 외래 키 매핑을 그대로 해석한다.
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        for (var candidate : scanner.findCandidateComponents("com.server.zelo.domain")) {
            sources.addAnnotatedClass(ClassUtils.forName(candidate.getBeanClassName(),
                    OrderItemSchemaDefaultTest.class.getClassLoader()));
        }
        metadata = sources.buildMetadata();
    }

    @AfterAll
    static void closeMetadata() {
        if (registry != null) {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    @Test
    void 새_테이블의_취소_수량_컬럼은_기본값_0과_NOT_NULL로_생성된다() {
        // given
        SchemaCreatorImpl creator = new SchemaCreatorImpl(registry);

        // when
        String createTable = creator.generateCreationCommands(metadata, false).stream()
                .map(OrderItemSchemaDefaultTest::normalize)
                .filter(sql -> sql.startsWith("create table order_items "))
                .findFirst()
                .orElseThrow(() -> new AssertionError("order_items 생성 DDL이 없습니다."));

        // then
        assertDefaultAndNotNull(createTable);
    }

    @Test
    void 기존_테이블에_취소_수량_컬럼을_추가할_때도_기본값_0을_포함한다() {
        // given
        var table = metadata.getEntityBinding(OrderItem.class.getName()).getTable();
        var database = metadata.getDatabase();
        var context = SqlStringGenerationContextImpl.fromConfigurationMapForMigration(
                database.getJdbcEnvironment(), database, Map.of());
        var migrator = new StandardTableMigrator(database.getDialect());
        // 저장소 메타데이터에 없는 컬럼에 대해 Hibernate의 실제 추가 DDL을 생성한다.

        // when
        String addColumn = Arrays.stream(migrator.getSqlAlterStrings(table, metadata, existingTable, context))
                .map(OrderItemSchemaDefaultTest::normalize)
                .filter(sql -> sql.contains("add column canceled_quantity "))
                .findFirst()
                .orElseThrow(() -> new AssertionError("canceled_quantity 추가 DDL이 없습니다."));

        // then
        assertThat(addColumn).startsWith("alter table order_items ");
        assertDefaultAndNotNull(addColumn);
    }

    private static void assertDefaultAndNotNull(String sql) {
        Matcher column = Pattern.compile("\\bcanceled_quantity\\s+([^,)]*)").matcher(sql);
        assertThat(column.find()).as("취소 수량 컬럼이 DDL에 있어야 합니다: %s", sql).isTrue();
        assertThat(column.group(1)).contains("integer", "default 0", "not null");
    }

    private static String normalize(String sql) {
        return sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
