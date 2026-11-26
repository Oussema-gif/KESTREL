package com.kestrel.maintenance;

import static com.kestrel.maintenance.ApiModels.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

final class PagedQuery<T> {
    private final String from;
    private final String columns;
    private final String orderBy;
    private final RowMapper<T> mapper;
    private final StringBuilder where = new StringBuilder(" WHERE true");
    private final Map<String, Object> params = new LinkedHashMap<>();

    PagedQuery(String from, String columns, String orderBy, RowMapper<T> mapper) {
        this.from = from;
        this.columns = columns;
        this.orderBy = orderBy;
        this.mapper = mapper;
    }

    void filter(String column, Object value) {
        if (value != null) {
            String key = "p" + params.size();
            where.append(" AND ").append(column).append(" = :").append(key);
            params.put(key, value instanceof Enum<?> e ? e.name() : value);
        }
    }

    Page<T> run(JdbcClient db, int page, int size) {
        String query = " FROM " + from + where;
        long total = db.sql("SELECT count(*)" + query).params(params).query(Long.class).single();
        var items = db.sql("SELECT " + columns + query + " ORDER BY " + orderBy + " LIMIT :limit OFFSET :offset")
                .params(params).param("limit", size).param("offset", (long) page * size).query(mapper).list();
        return new Page<>(items, page, size, total);
    }
}
