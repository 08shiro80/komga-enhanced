package org.gotson.komga.infrastructure.datasource

import org.flywaydb.core.Flyway
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import javax.sql.DataSource

@Component
class FlywayThumbnailsMigrationInitializer(
  @Qualifier("thumbnailsDataSourceRW")
  private val thumbnailsDataSource: DataSource,
) : InitializingBean {
  // by default Spring Boot will perform migration only on the @Primary datasource
  override fun afterPropertiesSet() {
    Flyway
      .configure()
      .locations("classpath:thumbnails/migration/sqlite")
      .dataSource(thumbnailsDataSource)
      .load()
      .apply {
        migrate()
      }
  }
}
