package com.songcan.yupicture.shared.sharding;

import com.baomidou.mybatisplus.extension.toolkit.SqlRunner;
import com.songcan.yupicture.domain.space.entity.Space;
import com.songcan.yupicture.domain.space.valueobject.SpaceLevelEnum;
import com.songcan.yupicture.domain.space.valueobject.SpaceTypeEnum;
import com.songcan.yupicture.application.service.SpaceApplicationService;

import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.driver.jdbc.core.connection.ShardingSphereConnection;
import org.apache.shardingsphere.infra.metadata.database.rule.ShardingSphereRuleMetaData;
import org.apache.shardingsphere.mode.manager.ContextManager;
import org.apache.shardingsphere.sharding.api.config.ShardingRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableRuleConfiguration;
import org.apache.shardingsphere.sharding.rule.ShardingRule;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

//@Component
@Slf4j
public class DynamicShardingManager {

    @Resource
    private DataSource dataSource;

    @Resource
    private SpaceApplicationService spaceService;

    private static final String LOGIC_TABLE_NAME = "picture";

    private static final String DATABASE_NAME = "logic_db"; // 配置文件中的数据库名称

    @PostConstruct
    public void initialize() {
        log.info("初始化动态分表配置...");
        createAllSpacePictureTables();
        updateShardingTableNodes();
    }

    /**
     * 动态创建图片空间分表
     * @param space
     */
    public synchronized void createSpacePictureTable(Space space) {
        if (!isShardingSpace(space)) {
            return;
        }
        if (createPictureTable(space.getId())) {
            updateShardingTableNodes();
        }
    }

    /**
     * 删除图片空间分表
     * @param space
     */
    public synchronized void dropSpacePictureTable(Space space) {
        if (space == null || space.getId() == null) {
            return;
        }
        try {
            updateShardingTableNodes();
        } catch (Exception e) {
            log.error("更新动态分表配置失败，跳过删除分表，空间 id = {}", space.getId(), e);
            return;
        }
        try {
            SqlRunner.db().update("DROP TABLE IF EXISTS " + buildTableName(space.getId()));
        } catch (Exception e) {
            log.error("删除图片空间分表失败，空间 id = {}", space.getId(), e);
        }
    }

    private void createAllSpacePictureTables() {
        for (Space space : fetchShardingSpaces()) {
            createPictureTable(space.getId());
        }
    }

    private boolean createPictureTable(Long spaceId) {
        String tableName = buildTableName(spaceId);
        try {
            SqlRunner.db().update("CREATE TABLE IF NOT EXISTS " + tableName + " LIKE " + LOGIC_TABLE_NAME);
            return true;
        } catch (Exception e) {
            log.error("创建图片空间分表失败，空间 id = {}", spaceId, e);
            return false;
        }
    }

    private boolean isShardingSpace(Space space) {
        return space != null
                && Objects.equals(space.getSpaceType(), SpaceTypeEnum.TEAM.getValue())
                && Objects.equals(space.getSpaceLevel(), SpaceLevelEnum.FLAGSHIP.getValue());
    }

    private List<Space> fetchShardingSpaces() {
        return spaceService.lambdaQuery()
                .eq(Space::getSpaceType, SpaceTypeEnum.TEAM.getValue())
                .eq(Space::getSpaceLevel, SpaceLevelEnum.FLAGSHIP.getValue())
                .list();
    }

    private String buildTableName(Long spaceId) {
        return LOGIC_TABLE_NAME + "_" + spaceId;
    }

    /**
     * 获取所有动态表名，包括初始表 picture 和分表 picture_{spaceId}
     */
    private Set<String> fetchAllPictureTableNames() {
        Set<String> tableNames = fetchShardingSpaces().stream()
                .map(space -> buildTableName(space.getId()))
                .collect(Collectors.toSet());
        tableNames.add(LOGIC_TABLE_NAME); // 添加初始逻辑表
        return tableNames;
    }

    /**
     * 更新 ShardingSphere 的 actual-data-nodes 动态表名配置
     */
    private synchronized void updateShardingTableNodes() {
        Set<String> tableNames = fetchAllPictureTableNames();
        String newActualDataNodes = tableNames.stream()
                .map(tableName -> "yu_picture." + tableName) // 确保前缀合法
                .collect(Collectors.joining(","));
        log.info("动态分表 actual-data-nodes 配置: {}", newActualDataNodes);

        ContextManager contextManager = getContextManager();
        ShardingSphereRuleMetaData ruleMetaData = contextManager.getMetaDataContexts()
                .getMetaData()
                .getDatabases()
                .get(DATABASE_NAME)
                .getRuleMetaData();

        Optional<ShardingRule> shardingRule = ruleMetaData.findSingleRule(ShardingRule.class);
        if (shardingRule.isPresent()) {
            ShardingRuleConfiguration ruleConfig = (ShardingRuleConfiguration) shardingRule.get().getConfiguration();
            List<ShardingTableRuleConfiguration> updatedRules = ruleConfig.getTables()
                    .stream()
                    .map(oldTableRule -> {
                        if (LOGIC_TABLE_NAME.equals(oldTableRule.getLogicTable())) {
                            ShardingTableRuleConfiguration newTableRuleConfig = new ShardingTableRuleConfiguration(LOGIC_TABLE_NAME, newActualDataNodes);
                            newTableRuleConfig.setDatabaseShardingStrategy(oldTableRule.getDatabaseShardingStrategy());
                            newTableRuleConfig.setTableShardingStrategy(oldTableRule.getTableShardingStrategy());
                            newTableRuleConfig.setKeyGenerateStrategy(oldTableRule.getKeyGenerateStrategy());
                            newTableRuleConfig.setAuditStrategy(oldTableRule.getAuditStrategy());
                            return newTableRuleConfig;
                        }
                        return oldTableRule;
                    })
                    .collect(Collectors.toList());
            ruleConfig.setTables(updatedRules);
            contextManager.alterRuleConfiguration(DATABASE_NAME, Collections.singleton(ruleConfig));
            contextManager.reloadDatabase(DATABASE_NAME);
            log.info("动态分表规则更新成功！");
        } else {
            log.error("未找到 ShardingSphere 的分片规则配置，动态分表更新失败。");
        }
    }

    /**
     * 获取 ShardingSphere ContextManager
     */
    private ContextManager getContextManager() {
        try (ShardingSphereConnection connection = dataSource.getConnection().unwrap(ShardingSphereConnection.class)) {
            return connection.getContextManager();
        } catch (SQLException e) {
            throw new RuntimeException("获取 ShardingSphere ContextManager 失败", e);
        }
    }
}
