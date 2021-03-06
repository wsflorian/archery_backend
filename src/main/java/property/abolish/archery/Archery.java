package property.abolish.archery;

import com.google.gson.Gson;
import io.javalin.Javalin;
import io.javalin.core.security.Role;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.plugin.json.JavalinJson;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Handles;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.JdbiException;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import property.abolish.archery.db.model.User;
import property.abolish.archery.db.model.UserSession;
import property.abolish.archery.db.query.UserQuery;
import property.abolish.archery.db.query.UserSessionQuery;
import property.abolish.archery.http.controller.*;
import property.abolish.archery.http.model.responses.ErrorResponse;
import property.abolish.archery.utilities.General;

import java.io.IOException;
import java.time.Instant;

import static io.javalin.apibuilder.ApiBuilder.*;
import static io.javalin.core.security.SecurityUtil.roles;
import static property.abolish.archery.http.controller.UserController.COOKIE_NAME_SESSION;
import static property.abolish.archery.utilities.General.handleException;

public class Archery {
    private static Jdbi jdbi;
    private static Config config;

    public enum MyRole implements Role {
        ANYONE, LOGGED_IN
    }

    public static void main(String[] args) {

        try {
            config = Config.load();
        } catch (IOException e) {
            handleException("Config couldn't be loaded", e);
            return;
        }

        jdbi = Jdbi.create(String.format("jdbc:mysql://%s:%d/%s?serverTimezone=%s&zerodatetimebehavior=converttonull", config.dbIp, config.dbPort, config.dbName, config.dbTimezone), config.dbUser, config.dbPw);
        jdbi.installPlugin(new SqlObjectPlugin());
        jdbi.getConfig(Handles.class).setForceEndTransactions(false);

        Gson gson = General.getGson();
        JavalinJson.setFromJsonMapper(gson::fromJson);
        //noinspection NullableProblems
        JavalinJson.setToJsonMapper(gson::toJson);

        try (Handle dbConnection = getConnection()) {
            System.out.println("Connection successfully established!");

            Javalin httpServer = Javalin.create(config -> {
                if (Archery.config.devModeURL != null) {
                    config.enableCorsForOrigin(Archery.config.devModeURL);
                }

                config.accessManager((handler, ctx, permittedRoles) -> {
                    if (ctx.method().equals("OPTIONS")) {
                        handler.handle(ctx);
                        return;
                    }

                    MyRole userRole = getUserRole(ctx);

                    if (permittedRoles.contains(userRole)) {
                        handler.handle(ctx);
                    } else {
                        ctx.status(401).json(new ErrorResponse("UNAUTHORIZED_USER", "User is not authorized for this action"));
                    }
                });
            }).start("localhost", config.webPort);

            //noinspection CodeBlock2Expr
            httpServer.routes(() -> {
                path("api/v1", () -> {
                    path("users", () -> {
                        path("session", () -> {
                            put(UserController::handleLogin, roles(MyRole.ANYONE, MyRole.LOGGED_IN));
                            delete(UserController::handleSignOff, roles(MyRole.LOGGED_IN));
                            get(UserController::handleGetUser, roles(MyRole.LOGGED_IN));
                        });
                        post(UserController::handleGetUsersBySearchTerm, roles(MyRole.LOGGED_IN));
                        put(UserController::handleRegister, roles(MyRole.ANYONE, MyRole.LOGGED_IN));
                    });
                    path("events", () -> {
                        get(EventController::handleGetEventList, roles(MyRole.LOGGED_IN));
                        put(EventController::handleCreateEvent, roles(MyRole.LOGGED_IN));
                        path(":eventId", () -> {
                            path("shots", () ->
                                    put(ShotController::handleAddShot, roles(MyRole.LOGGED_IN)));
                            path("stats", () ->
                                    get(StatsController::handleGetEventStats, roles(MyRole.LOGGED_IN)));
                            get(EventController::handleGetEventInfo, roles(MyRole.LOGGED_IN));
                        });
                    });
                    path("parkours", () -> {
                        put(ParkourController::handleCreateParkour, roles(MyRole.LOGGED_IN));
                        get(ParkourController::handleGetParkourList, roles(MyRole.LOGGED_IN));
                    });
                    path("gamemodes", () ->
                            get(EventController::handleGetGameModes, roles(MyRole.LOGGED_IN)));
                    path("stats/:gameModeId", () -> {
                        get("numbers", StatsController::handleGetOverallStatsNumbers, roles(MyRole.LOGGED_IN));
                        get("graph", StatsController::handleGetOverallStatsGraph, roles(MyRole.LOGGED_IN));
                    });
                });
            });

            httpServer.exception(Exception.class, (exception, ctx) -> {
                exception.printStackTrace();
                ctx.status(500).json(new ErrorResponse("INTERNAL_SERVER_ERROR", "A internal server error has occurred"));
            });

            httpServer.exception(BadRequestResponse.class, ((exception, ctx) ->
                    ctx.status(400).json(new ErrorResponse("VALIDATION_ERROR", exception.getMessage()))));

        } catch (JdbiException e) {
            handleException("Connection couldn't be established", e);
        }
    }

    public static Jdbi getJdbi() {
        return jdbi;
    }

    public static Config getConfig() {
        return config;
    }

    public static Handle getConnection() {
        Handle connection = getJdbi().open();
        try {
            connection.getConnection().setAutoCommit(false);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return connection;
    }

    private static MyRole getUserRole(Context ctx) {
        String sessionId = ctx.cookie(COOKIE_NAME_SESSION);

        if (sessionId == null || sessionId.isEmpty()) {
            return MyRole.ANYONE;
        }

        try (Handle dbConnection = getConnection()) {

            // Check if sessionId is valid
            UserSessionQuery userSessionQuery = dbConnection.attach(UserSessionQuery.class);
            UserSession userSession = userSessionQuery.getUserSessionBySessionId(sessionId);

            if (userSession == null || userSession.getExpiryDate().isBefore(Instant.now())) {
                return MyRole.ANYONE;
            }

            ctx.register(UserSession.class, userSession);
            UserQuery userQuery = dbConnection.attach(UserQuery.class);
            User user = userQuery.getUserByUserId(userSession.getUserId());
            ctx.register(User.class, user);

            return MyRole.LOGGED_IN;
        }
    }
}
