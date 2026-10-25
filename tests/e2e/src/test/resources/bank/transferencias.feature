Feature: Transferencias de punta a punta, como las usa un cliente (BANK-88)
  Todo por el gateway (:8080) con tokens reales de Keycloak, contra el entorno local de infra/start-local.sh.
  Las cuentas se buscan por la API (los ids cambian si se recrean los datos) y los montos son distintos en cada corrida.
  No borra ni reinicia nada: solo hace transferencias pequeñas desde la cuenta en soles de Ana

  Background:
    * def anaToken = callonce read('login.feature') { username: 'ana', password: 'ana-local' }
    * def anaToken = anaToken.token
    * def asesorToken = callonce read('login.feature') { username: 'asesor', password: 'asesor-local' }
    * def asesorToken = asesorToken.token
    * def cents = function(text) { return Math.round(parseFloat(text) * 100) }
    # Desde cuándo mirar la bandeja: los correos de corridas anteriores no cuentan
    * def since = new Date().toISOString()

    # La cuenta en soles de Ana, desde su propio token
    Given url gateway + '/api/accounts/me'
    And header Authorization = anaToken
    When method get
    Then status 200
    * def ana = karate.filter(response, function(a) { return a.currency == 'PEN' })[0]

    # La cuenta que recibe, buscada por el asesor (el cliente no puede ver cuentas ajenas)
    Given url gateway + '/api/accounts'
    And param customerId = destinoCustomerId
    And header Authorization = asesorToken
    When method get
    Then status 200
    * def destino = karate.filter(response, function(a) { return a.number == destinoNumber })[0]
    * match destino != null

    # El correo de Ana, para comprobar a quién le llegan los avisos
    Given url gateway + '/api/customers/me'
    And header Authorization = anaToken
    When method get
    Then status 200
    * def anaEmail = karate.filter(response.emails, function(e) { return e.primary })[0].address

    * def balanceOf = function(accountId) { return karate.call('balance.feature', { accountId: accountId }).cents }
    * def mailsAbout =
      """
      function(response, amount) {
        return karate.filter(response.messages, function(m) { return m.Created >= since && m.Subject.indexOf(amount + ' PEN') >= 0 });
      }
      """
    * configure retry = { count: 40, interval: 1000 }

  Scenario: Sin token no hay acceso, y un cliente no ve cuentas ajenas
    Given url gateway + '/api/accounts/me'
    When method get
    Then status 401

    Given url gateway + '/api/accounts/' + destino.id
    And header Authorization = anaToken
    When method get
    Then status 403

  Scenario: Ana transfiere, el core lo registra y llegan los dos correos
    * def amount = amount()
    * def anaBefore = balanceOf(ana.id)
    * def destinoBefore = balanceOf(destino.id)

    Given url gateway + '/api/transfers'
    And header Authorization = anaToken
    And header Idempotency-Key = java.util.UUID.randomUUID() + ''
    And request { fromAccountId: '#(ana.id)', toAccountNumber: '#(destinoNumber)', amount: '#(amount)' }
    When method post
    Then status 201
    And match response.status == 'COMPLETED'
    * def transferId = response.id

    Given url gateway + '/api/transfers/' + transferId
    And header Authorization = anaToken
    When method get
    Then status 200
    And match response.status == 'COMPLETED'

    # El aviso pasa por Kafka y notifications-service: se espera a que lleguen los dos (Enviaste y Recibiste)
    Given url mailpit + '/api/v1/search'
    And param query = amount
    And retry until mailsAbout(response, amount).length == 2
    When method get
    * def subjects = karate.map(mailsAbout(response, amount), function(m) { return m.Subject })
    * match subjects contains 'Enviaste ' + amount + ' PEN'
    * match subjects contains 'Recibiste ' + amount + ' PEN'

    # El dinero no se crea ni se pierde: lo que sale de Ana es lo que llega al destino
    * match balanceOf(ana.id) == anaBefore - cents(amount)
    * match balanceOf(destino.id) == destinoBefore + cents(amount)

  Scenario: El core rechaza (.13): el dinero vuelve y solo Ana recibe el aviso
    * def amount = amount('13')
    * def anaBefore = balanceOf(ana.id)
    * def destinoBefore = balanceOf(destino.id)

    Given url gateway + '/api/transfers'
    And header Authorization = anaToken
    And header Idempotency-Key = java.util.UUID.randomUUID() + ''
    And request { fromAccountId: '#(ana.id)', toAccountNumber: '#(destinoNumber)', amount: '#(amount)' }
    When method post
    Then status 422
    And match response.transferStatus == 'FAILED'
    And match response.reason == '057'

    Given url mailpit + '/api/v1/search'
    And param query = amount
    And retry until mailsAbout(response, amount).length >= 1
    When method get
    # Un rato más, por si llegara un aviso de sobra al destino
    * java.lang.Thread.sleep(3000)
    Given url mailpit + '/api/v1/search'
    And param query = amount
    When method get
    * def mails = mailsAbout(response, amount)
    * match mails == '#[1]'
    * match mails[0].Subject == 'No pudimos completar tu transferencia de ' + amount + ' PEN'
    * match mails[0].To[0].Address == anaEmail

    * match balanceOf(ana.id) == anaBefore
    * match balanceOf(destino.id) == destinoBefore

  Scenario: El core no responde a tiempo (.99): queda pendiente y el recuperador la termina
    * def amount = amount('99')
    * def anaBefore = balanceOf(ana.id)
    * def destinoBefore = balanceOf(destino.id)

    Given url gateway + '/api/transfers'
    And header Authorization = anaToken
    And header Idempotency-Key = java.util.UUID.randomUUID() + ''
    And request { fromAccountId: '#(ana.id)', toAccountNumber: '#(destinoNumber)', amount: '#(amount)' }
    When method post
    Then status 202
    And match response.status == 'PENDING'
    * def transferId = response.id

    # El recuperador corre cada 5 s y toma las pendientes con más de 10 s
    Given url gateway + '/api/transfers/' + transferId
    And header Authorization = anaToken
    And retry until response.status == 'COMPLETED'
    When method get
    Then status 200

    * match balanceOf(ana.id) == anaBefore - cents(amount)
    * match balanceOf(destino.id) == destinoBefore + cents(amount)
