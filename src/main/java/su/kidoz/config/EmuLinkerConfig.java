package su.kidoz.config;

import java.time.Duration;
import java.util.List;
import su.kidoz.kaillera.access.FileBasedAccessManager;
import su.kidoz.kaillera.controller.KailleraServerController;
import su.kidoz.kaillera.controller.connectcontroller.ConnectController;
import su.kidoz.kaillera.controller.v086.V086Controller;
import su.kidoz.kaillera.controller.v086.action.ActionRouter;
import su.kidoz.kaillera.controller.v086.action.V086Action;
import su.kidoz.kaillera.controller.v086.action.V086GameEventHandler;
import su.kidoz.kaillera.controller.v086.action.V086ServerEventHandler;
import su.kidoz.kaillera.controller.v086.action.V086UserEventHandler;
import su.kidoz.kaillera.master.MasterListStatsCollector;
import su.kidoz.kaillera.master.client.MasterListUpdaterImpl;
import su.kidoz.kaillera.metrics.GameMetricsCollector;
import su.kidoz.kaillera.metrics.ServerMetrics;
import su.kidoz.kaillera.model.impl.AutoFireDetectorFactoryImpl;
import su.kidoz.kaillera.model.impl.KailleraServerImpl;
import su.kidoz.kaillera.release.KailleraServerReleaseInfo;
import su.kidoz.kaillera.service.ServerPolicyServices;

import su.kidoz.kaillera.model.impl.GameManager;
import su.kidoz.kaillera.model.impl.UserManager;
import su.kidoz.kaillera.model.validation.LoginValidator;
import su.kidoz.kaillera.service.AnnouncementService;
import su.kidoz.kaillera.service.ChatModerationService;
import su.kidoz.kaillera.service.GameService;
import su.kidoz.kaillera.service.UserService;
import su.kidoz.kaillera.service.impl.GameServiceImpl;
import su.kidoz.kaillera.service.impl.UserServiceImpl;
import su.kidoz.util.EmuLinkerExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({ServerConfig.class, GameConfig.class, MasterListConfig.class,
        ControllersConfig.class})
public class EmuLinkerConfig {

    @Bean
    public EmuLinkerExecutor emuLinkerExecutor() {
        return new EmuLinkerExecutor();
    }

    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(5)).withReadTimeout(Duration.ofSeconds(5));
        return builder.requestFactory(ClientHttpRequestFactoryBuilder.simple().build(settings))
                .build();
    }

    @Bean
    public KailleraServerReleaseInfo kailleraServerReleaseInfo() {
        return new KailleraServerReleaseInfo();
    }

    @Bean
    public FileBasedAccessManager fileBasedAccessManager(EmuLinkerExecutor executor)
            throws Exception {
        return new FileBasedAccessManager(executor);
    }

    @Bean
    public ConnectController connectController(EmuLinkerExecutor executor,
            V086Controller v086Controller, FileBasedAccessManager accessManager,
            ControllersConfig controllersConfig) throws Exception {
        KailleraServerController[] controllers = new KailleraServerController[]{v086Controller};
        return new ConnectController(executor, controllers, accessManager, controllersConfig);
    }

    @Bean
    public V086Controller v086Controller(KailleraServerImpl kailleraServer,
            EmuLinkerExecutor executor, ControllersConfig controllersConfig,
            ServerConfig serverConfig, ActionRouter actionRouter) throws Exception {
        return new V086Controller(kailleraServer, executor, controllersConfig, serverConfig,
                actionRouter);
    }

    @Bean
    public LoginValidator loginValidator(FileBasedAccessManager accessManager,
            ServerConfig serverConfig) {
        return new LoginValidator(accessManager, serverConfig);
    }

    @Bean
    public ChatModerationService chatModerationService(FileBasedAccessManager accessManager,
            ServerConfig serverConfig) {
        return new ChatModerationService(accessManager, serverConfig.getChatFloodTime(),
                serverConfig.getMaxChatLength());
    }

    @Bean
    public AnnouncementService announcementService() {
        return new AnnouncementService();
    }

    @Bean
    public UserManager userManager(ServerConfig serverConfig) {
        return new UserManager(serverConfig.getMaxUsers());
    }

    @Bean
    public GameManager gameManager(ServerConfig serverConfig) {
        return new GameManager(serverConfig.getMaxUsers());
    }

    @Bean
    public ServerInfrastructure serverInfrastructure(EmuLinkerExecutor executor,
            FileBasedAccessManager accessManager, KailleraServerReleaseInfo releaseInfo) {
        return new ServerInfrastructure(executor, accessManager, releaseInfo);
    }

    @Bean
    public ServerPolicyServices serverPolicyServices(LoginValidator loginValidator,
            ChatModerationService chatModerationService, AnnouncementService announcementService) {
        return new ServerPolicyServices(loginValidator, chatModerationService, announcementService);
    }

    @Bean
    public ServerMetrics serverMetrics(MasterListStatsCollector statsCollector,
            GameMetricsCollector gameMetricsCollector) {
        return new ServerMetrics(statsCollector, gameMetricsCollector);
    }

    @Bean
    public ServerConfigs serverConfigs(ServerConfig serverConfig, GameConfig gameConfig,
            MasterListConfig masterListConfig) {
        return new ServerConfigs(serverConfig, gameConfig, masterListConfig);
    }

    @Bean
    public KailleraServerImpl kailleraServerImpl(ServerInfrastructure infrastructure,
            ServerConfigs configs, ServerPolicyServices policyServices, ServerMetrics metrics,
            AutoFireDetectorFactoryImpl autoFireFactory, UserManager userManager,
            GameManager gameManager) throws Exception {
        return new KailleraServerImpl(infrastructure, configs, policyServices, metrics,
                autoFireFactory, userManager, gameManager);
    }

    @Bean
    public AutoFireDetectorFactoryImpl autoFireDetectorFactoryImpl() {
        return new AutoFireDetectorFactoryImpl();
    }

    @Bean
    public MasterListStatsCollector masterListStatsCollector() {
        return new MasterListStatsCollector();
    }

    @Bean
    public MasterListUpdaterImpl masterListUpdaterImpl(MasterListConfig masterListConfig,
            EmuLinkerExecutor executor, ConnectController connectController,
            KailleraServerImpl kailleraServer, MasterListStatsCollector statsCollector,
            KailleraServerReleaseInfo releaseInfo, RestClient restClient) throws Exception {
        return new MasterListUpdaterImpl(masterListConfig, executor, connectController,
                kailleraServer, statsCollector, releaseInfo, restClient);
    }

    // Service layer beans

    @Bean
    public UserService userService(KailleraServerImpl kailleraServer) {
        return new UserServiceImpl(kailleraServer);
    }

    @Bean
    public GameService gameService(KailleraServerImpl kailleraServer) {
        return new GameServiceImpl(kailleraServer);
    }

    @Bean
    public ActionRouter actionRouter(List<V086Action> actions,
            List<V086ServerEventHandler> serverEventHandlers,
            List<V086GameEventHandler> gameEventHandlers,
            List<V086UserEventHandler> userEventHandlers) {
        return new ActionRouter(actions, serverEventHandlers, gameEventHandlers, userEventHandlers);
    }
}
