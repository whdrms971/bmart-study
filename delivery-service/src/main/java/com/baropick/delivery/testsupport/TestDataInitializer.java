package com.baropick.delivery.testsupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.baropick.delivery.config.DeliveryProperties;
import com.baropick.delivery.domain.Rider;
import com.baropick.delivery.domain.RiderRepository;

/** local-test: 테스트 라이더 계정을 Delivery 라이더 원장에 등록(없을 때만). */
@Component
@Profile("local-test")
public class TestDataInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(TestDataInitializer.class);
    private final RiderRepository riders;
    private final DeliveryProperties props;

    public TestDataInitializer(RiderRepository riders, DeliveryProperties props) {
        this.riders = riders;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (String id : props.testAccounts().riders()) {
            if (!riders.existsById(id)) riders.save(new Rider(id, "테스트 라이더 " + id));
        }
        if (props.testAccounts().passcode() == null || props.testAccounts().passcode().length() < 8) {
            log.warn("DELIVERY_TEST_PASSCODE(8자 이상) 미설정 — 테스트 로그인이 거부됩니다. README 참조");
        }
    }
}
