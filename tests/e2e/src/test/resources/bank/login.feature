@ignore
Feature: Token real de Keycloak (lo llaman los escenarios con username y password)

  Scenario:
    Given url keycloak + '/realms/bank/protocol/openid-connect/token'
    # Password grant del cliente público bank-cli, como el login de la app; usuarios de prueba del realm local
    And form field grant_type = 'password'
    And form field client_id = 'bank-cli'
    And form field username = username
    And form field password = password
    When method post
    Then status 200
    * def token = 'Bearer ' + response.access_token
