package com.epam.drill.plugins.coverage.routes

import io.ktor.locations.*

class Routes {

    @Location("/active-scope")
    object ActiveScope

    @Location("/active-sessions")
    object ActiveSessions

    @Location("/scopes")
    object Scopes


    @Location("/scope")
    class Scope {
        @Location("/{scopeId}")
        class Scope(val scopeId: String)


        @Location("/{scopeId}/associated-tests")
        class AssociatedTests(val scopeId: String)

        @Location("/{scopeId}/methods")
        class Methods(val scopeId: String)

        @Location("/{scopeId}/tests-usages")
        class TestsUsages(val scopeId: String)

        @Location("/{scopeId}/coverage-by-packages")
        class CoverageByPackages(val scopeId: String)

        @Location("/{scopeId}/coverage")
        class Coverage(val scopeId: String)
    }

    @Location("/build")
    class Build {
        @Location("/associated-tests")
        object AssociatedTests

        @Location("/methods")
        object Methods

        @Location("/tests-usages")
        object TestsUsages

        @Location("/coverage-by-packages")
        object CoverageByPackages

        @Location("/coverage")
        object Coverage

        @Location("/risks")
        object Risks

        @Location("/tests-to-run")
        object TestsToRun

        @Location("/coverage-new")
        object CoverageNew
    }
}