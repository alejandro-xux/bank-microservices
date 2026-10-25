@ignore
Feature: Saldo de una cuenta en céntimos (lo lee el asesor, que puede ver cualquier cuenta)

  Scenario:
    Given url gateway + '/api/accounts/' + accountId
    And header Authorization = asesorToken
    When method get
    Then status 200
    * def cents = Math.round(parseFloat(response.balance) * 100)
