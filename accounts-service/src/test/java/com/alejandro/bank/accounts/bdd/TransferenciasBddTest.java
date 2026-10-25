package com.alejandro.bank.accounts.bdd;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

// Corre los escenarios de src/test/resources/features con JUnit Platform (BANK-87): están en la tarea test, en la CI y
// en el resumen de cada PR como cualquier otra prueba. El reporte legible queda en build/reports/cucumber
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.alejandro.bank.accounts.bdd")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty, html:build/reports/cucumber/transferencias.html")
class TransferenciasBddTest {
}
