package com.yanban.api.invite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Transactional;

@Configuration
@EnableConfigurationProperties(InviteCodeProperties.class)
public class InviteCodeInitializer {

    private static final Logger log = LoggerFactory.getLogger(InviteCodeInitializer.class);

    @Bean
    public ApplicationRunner inviteCodeSeeder(InviteCodeProperties properties,
                                              InviteCodeRepository repository) {
        return args -> seedInviteCodes(properties, repository);
    }

    @Transactional
    void seedInviteCodes(InviteCodeProperties properties, InviteCodeRepository repository) {
        if (!properties.isEnabled()) {
            log.info("Invite code feature is disabled, skipping seed.");
            return;
        }
        var codes = properties.parseCodes();
        if (codes.isEmpty()) {
            log.warn("Invite code feature is enabled but no codes configured (yanban.invite.codes).");
            return;
        }
        int inserted = 0;
        for (String code : codes) {
            if (repository.findByCode(code).isEmpty()) {
                repository.saveAndFlush(new InviteCode(code, properties.getMaxUses()));
                inserted++;
            }
        }
        log.info("Invite code seed complete: {} codes configured ({} newly inserted, {} already existed).",
                codes.size(), inserted, codes.size() - inserted);
    }
}
