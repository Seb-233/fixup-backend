package com.fixup.analytics.infrastructure;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JpaMarketIndicatorSnapshotsTest {

    @Test
    void failsFastWithIllegalStateExceptionWhenDataSourceIsNull() {
        var repository = mock(MarketIndicatorSnapshotJpaRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.getDataSource()).thenReturn(null);

        assertThatThrownBy(() -> new JpaMarketIndicatorSnapshots(repository, jdbc, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DataSource is null");
    }

    @Test
    void failsFastWithIllegalStateExceptionWhenConnectionFails() throws Exception {
        var repository = mock(MarketIndicatorSnapshotJpaRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var dataSource = mock(DataSource.class);
        when(jdbc.getDataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));

        assertThatThrownBy(() -> new JpaMarketIndicatorSnapshots(repository, jdbc, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to determine database engine");
    }

    @Test
    void failsFastWithIllegalStateExceptionWhenDatabaseProductNameIsEmpty() throws Exception {
        var repository = mock(MarketIndicatorSnapshotJpaRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var dataSource = mock(DataSource.class);
        var connection = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);

        when(jdbc.getDataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("");

        assertThatThrownBy(() -> new JpaMarketIndicatorSnapshots(repository, jdbc, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Database product name is empty");
    }

    @Test
    void detectsPostgresAndH2EnginesWithoutError() throws Exception {
        var repository = mock(MarketIndicatorSnapshotJpaRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var dataSource = mock(DataSource.class);
        var connection = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);

        when(jdbc.getDataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);

        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        var pgInstance = new JpaMarketIndicatorSnapshots(repository, jdbc, Clock.systemUTC());
        assertThat(pgInstance).isNotNull();

        when(metadata.getDatabaseProductName()).thenReturn("H2");
        var h2Instance = new JpaMarketIndicatorSnapshots(repository, jdbc, Clock.systemUTC());
        assertThat(h2Instance).isNotNull();
    }
}
