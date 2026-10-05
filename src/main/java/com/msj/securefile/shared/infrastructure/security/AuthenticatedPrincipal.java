package com.msj.securefile.shared.infrastructure.security;

/**
 * What the other contexts may know about the authenticated caller: its id, as the long of its TSID. The auth context
 * implements it on its principal, the others read it from the security context, so none of them knows the other's types.
 */
public interface AuthenticatedPrincipal {

    long id();
}