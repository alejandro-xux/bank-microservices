// Configuración de la prueba de punta a punta (BANK-88). Las URLs son las del entorno local por defecto y se cambian
// con -Dgateway=... -Dkeycloak=... -Dmailpit=... Los usuarios ana y asesor son los de prueba del realm local
// (infra/keycloak/bank-realm.json): no existen fuera de la máquina de desarrollo
function fn() {
  var config = {
    gateway: karate.properties['gateway'] || 'http://localhost:8080',
    keycloak: karate.properties['keycloak'] || 'http://localhost:8180',
    mailpit: karate.properties['mailpit'] || 'http://localhost:8025',
    // El cliente que recibe en las pruebas (infra/seed-local.sh, DESTINO): su cuenta en soles es 001-0000000003-2
    destinoCustomerId: '08d38a3b-c398-48fb-be36-3d6a1dde7ad2',
    destinoNumber: '001-0000000003-2'
  };
  // Un monto que no choca con otras corridas ni con las reglas del mainframe (que miran los céntimos):
  // entero entre 1 y 9 y los céntimos que se pidan, o al azar entre 20 y 89
  config.amount = function (cents) {
    var units = 1 + Math.floor(Math.random() * 9);
    var c = cents ? cents : '' + (20 + Math.floor(Math.random() * 70));
    return units + '.' + c;
  };
  // Si no responde a tiempo, la prueba falla con un mensaje en vez de quedarse esperando (D8: sin colgarse)
  karate.configure('connectTimeout', 5000);
  karate.configure('readTimeout', 10000);
  return config;
}
