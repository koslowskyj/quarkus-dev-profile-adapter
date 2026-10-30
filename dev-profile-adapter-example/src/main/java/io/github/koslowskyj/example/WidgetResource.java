package io.github.koslowskyj.example;

import io.agroal.api.AgroalDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

@Path("/widgets")
public class WidgetResource {

    @Inject
    AgroalDataSource dataSource;

    @GET
    public List<Widget> list() throws SQLException {
        final List<Widget> widgets = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("select id, name from widget order by id")) {
            while (rs.next()) {
                widgets.add(new Widget(rs.getLong("id"), rs.getString("name")));
            }
        }
        return widgets;
    }

    @POST
    public Widget add(@QueryParam("name") String name) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection
                        .prepareStatement("insert into widget (name) values (?) returning id")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Widget(rs.getLong("id"), name);
            }
        }
    }
}
