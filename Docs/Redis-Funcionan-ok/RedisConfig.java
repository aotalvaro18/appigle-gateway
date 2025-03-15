package com.appigle.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Configuración para Redis reactivo.
 * 
 * Esta clase proporciona la configuración necesaria para usar Redis
 * con operaciones reactivas, que es especialmente útil para la lista
 * negra de tokens y rate limiting.
 */
@Configuration
public class RedisConfig {

    /**
     * Configura un ReactiveRedisTemplate para operaciones con String/String
     * 
     * @param connectionFactory Factory de conexiones Redis reactivas
     * @return ReactiveRedisTemplate configurado para String/String
     */
    @Bean
    @Primary // Para inyectar una dependencia en el constructor de TokenBlacklist
    public ReactiveRedisTemplate<String, String> reactiveRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory) {
        
        // Configurar serializadores para clave y valor
        StringRedisSerializer keySerializer = new StringRedisSerializer();
        StringRedisSerializer valueSerializer = new StringRedisSerializer();
        
        // Crear contexto de serialización
        RedisSerializationContext<String, String> serializationContext = 
                RedisSerializationContext.<String, String>newSerializationContext(RedisSerializer.string())
                        .key(keySerializer)
                        .value(valueSerializer)
                        .hashKey(keySerializer)
                        .hashValue(valueSerializer)
                        .build();
        
        // Crear el template con la factory y el contexto de serialización
        return new ReactiveRedisTemplate<>(connectionFactory, serializationContext);
    }
}