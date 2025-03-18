// Script simplificado para pruebas baseline del API Gateway AppIgle
import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';

// Métricas personalizadas
const failRate = new Rate('failed_requests');
const authLatency = new Trend('auth_latency');
const userServiceLatency = new Trend('user_service_latency');
const rateLimitedRequests = new Rate('rate_limited_requests');
const circuitBreakerTrips = new Rate('circuit_breaker_trips');

// Configuración de la prueba de baseline
export const options = {
  scenarios: {
    baseline: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        // Fase de calentamiento: usuarios aumentan gradualmente
        { duration: '30s', target: 30 },
        // Fase de carga normal: mantener nivel constante
        { duration: '2m', target: 30 },
        // Fase de finalización gradual
        { duration: '30s', target: 0 },
      ],
    }
  },
  thresholds: {
    // Umbrales de calidad más claros
    'http_req_duration': ['p(95)<1500'], // 95% de solicitudes deben completarse en menos de 1.5 segundos
    'http_req_failed': ['rate<0.05'],    // Menos del 5% de fallos
    'failed_requests': ['rate<0.05'],    // Menos del 5% de fallos en nuestras verificaciones
    'rate_limited_requests': ['rate<0.1'], // No más del 10% de solicitudes deben ser limitadas por rate limiting
  },
};

// Datos de autenticación - usuarios de prueba
const users = new SharedArray('users', function() {
  return [
    { email: 'test1@appigle.com', password: 'Password123!' },
    { email: 'test2@appigle.com', password: 'Password123!' },
    { email: 'test3@appigle.com', password: 'Password123!' },
  ];
});

// Variables globales
const BASE_URL = __ENV.API_URL || 'https://appigle-gateway.politedune-48459ced.eastus.azurecontainerapps.io';
const AUTH_LOGIN_ENDPOINT = `${BASE_URL}/api/auth/login`;
const AUTH_ME_ENDPOINT = `${BASE_URL}/api/auth/me`;
const USER_PROFILE_ENDPOINT = `${BASE_URL}/api/users/profile`;

// Almacenamiento de tokens
let authTokens = {};

// Función para autenticar un usuario
function authenticateUser(user) {
  const payload = JSON.stringify({
    email: user.email,
    password: user.password,
    rememberMe: false
  });
  
  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-Test-Source': 'k6-loadtest',
    },
    tags: { name: 'auth_login_request' },
  };
  
  const startTime = new Date().getTime();
  const res = http.post(AUTH_LOGIN_ENDPOINT, payload, params);
  const endTime = new Date().getTime();
  
  // Registrar latencia
  authLatency.add(endTime - startTime);
  
  // Verificar resultado
  const success = check(res, {
    'autenticación exitosa': (r) => r.status === 200,
    'respuesta incluye token': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.accessToken !== undefined;
      } catch (e) {
        return false;
      }
    },
  });
  
  if (!success) {
    failRate.add(1);
    console.log(`Error de autenticación para ${user.email}: ${res.status} ${res.body}`);
    return null;
  } else {
    failRate.add(0);
    
    try {
      const body = JSON.parse(res.body);
      
      if (body.accessToken) {
        return {
          accessToken: body.accessToken,
          refreshToken: body.refreshToken,
          userId: body.user?.id
        };
      }
    } catch (e) {
      console.log(`Error procesando respuesta de autenticación: ${e.message}`);
    }
  }
  
  // Verificar rate limiting
  if (res.status === 429) {
    rateLimitedRequests.add(1);
    console.log('Rate limiting detectado en autenticación');
  } else {
    rateLimitedRequests.add(0);
  }
  
  return null;
}

// Función para obtener el perfil de usuario
function getUserProfile(token) {
  if (!token) {
    console.log('No hay token disponible para prueba de perfil');
    failRate.add(1);
    return;
  }
  
  const params = {
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
      'X-Test-Source': 'k6-loadtest',
    },
    tags: { name: 'user_profile_request' },
  };
  
  const startTime = new Date().getTime();
  const res = http.get(USER_PROFILE_ENDPOINT, params);
  const endTime = new Date().getTime();
  
  userServiceLatency.add(endTime - startTime);
  
  const success = check(res, {
    'perfil recuperado': (r) => r.status === 200,
    'datos de usuario válidos': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.email !== undefined || body.id !== undefined;
      } catch (e) {
        return false;
      }
    },
  });
  
  if (!success) {
    // Verificar si el circuit breaker está abierto
    if (res.status === 503 && res.body.includes('circuit')) {
      circuitBreakerTrips.add(1);
      console.log('Circuit breaker detectado');
    } else {
      circuitBreakerTrips.add(0);
    }
    
    failRate.add(1);
  } else {
    failRate.add(0);
    circuitBreakerTrips.add(0);
  }
  
  // Verificar rate limiting
  if (res.status === 429) {
    rateLimitedRequests.add(1);
    console.log('Rate limiting detectado en perfil de usuario');
  } else {
    rateLimitedRequests.add(0);
  }
}

// Función setup - se ejecuta una vez al inicio de la prueba
export function setup() {
  console.log('Iniciando prueba de carga baseline para AppIgle API Gateway');
  
  // Pre-autenticar algunos usuarios para tener tokens disponibles
  for (const user of users) {
    const authData = authenticateUser(user);
    if (authData) {
      authTokens[user.email] = authData;
      console.log(`Usuario ${user.email} autenticado correctamente`);
    }
  }
  
  console.log(`Setup completado. ${Object.keys(authTokens).length} usuarios autenticados.`);
  
  // Si no pudimos autenticar ningún usuario, lanzar advertencia
  if (Object.keys(authTokens).length === 0) {
    console.log('⚠️ ADVERTENCIA: No se pudo autenticar ningún usuario. La prueba podría fallar.');
  }
  
  return { authTokens };
}

// Escenario principal
export default function() {
  // Seleccionar un escenario al azar, pero con mayor peso a operaciones de lectura
  const rand = Math.random();
  
  if (rand < 0.3) { // 30% de las solicitudes serán autenticaciones
    authenticationTest();
  } else { // 70% serán consultas de perfil
    userProfileTest();
  }
  
  // Pausa entre solicitudes para simular comportamiento de usuario real (1-3 segundos)
  sleep(1 + 2 * Math.random());
}

// Prueba de autenticación
function authenticationTest() {
  group('Autenticación', function() {
    // Seleccionar un usuario aleatorio
    const user = users[Math.floor(Math.random() * users.length)];
    
    // Intentar autenticar
    const authData = authenticateUser(user);
    
    // Si se autenticó correctamente, actualizar tokens
    if (authData) {
      authTokens[user.email] = authData;
    }
  });
}

// Prueba de obtención de perfil de usuario
function userProfileTest() {
  group('Perfil de Usuario', function() {
    // Obtener un token aleatorio
    const emails = Object.keys(authTokens);
    if (emails.length === 0) {
      console.log('No hay tokens disponibles para prueba de perfil');
      failRate.add(1);
      return;
    }
    
    const email = emails[Math.floor(Math.random() * emails.length)];
    const token = authTokens[email].accessToken;
    
    // Obtener perfil
    getUserProfile(token);
  });
}