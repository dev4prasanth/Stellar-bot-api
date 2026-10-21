package com.stellartrack.messaging.contract;

import com.stellartrack.common.json.JsonCodec;
import com.stellartrack.messaging.contract.layer1.Layer1Prediction;
import com.stellartrack.messaging.contract.layer1.Layer1PredictionContract;
import com.stellartrack.messaging.contract.layer2.Layer2StateChange;
import com.stellartrack.messaging.contract.layer2.Layer2StateChangeContract;
import com.stellartrack.messaging.contract.layer3.Layer3Ranking;
import com.stellartrack.messaging.contract.layer3.Layer3RankingContract;
import com.stellartrack.messaging.correlation.RaceMarketCorrelator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The approved contracts, modelled on what stellartrackbackend (Layer 1), the planetary state engine (Layer 2)
 * and the re-ranking engine (Layer 3) publish today. See docs/integrations/rabbitmq-contracts.md.
 */
@Configuration(proxyBeanMethods = false)
public class LayerContractsConfig {

    @Bean
    LayerContract<Layer1Prediction> layer1PredictionContract(JsonCodec json, RaceMarketCorrelator correlator) {
        return Layer1PredictionContract.create(json, correlator);
    }

    @Bean
    LayerContract<Layer2StateChange> layer2PlanetaryStateContract(JsonCodec json) {
        return Layer2StateChangeContract.create(json);
    }

    @Bean
    LayerContract<Layer3Ranking> layer3RankingContract(JsonCodec json, RaceMarketCorrelator correlator) {
        return Layer3RankingContract.create(json, correlator);
    }
}
