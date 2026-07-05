import { NextResponse, type NextRequest } from "next/server";

/**
 * UX-only gate: bounce clearly-unauthenticated visitors off app routes.
 * Real authorization always happens in the backend; every app page also
 * handles 401 from the API.
 */
export function proxy(request: NextRequest) {
  if (!request.cookies.has("verifolio_session")) {
    const login = new URL("/login", request.url);
    return NextResponse.redirect(login);
  }
  return NextResponse.next();
}

export const config = {
  matcher: [
    "/dashboard/:path*",
    "/requests/:path*",
    "/contacts/:path*",
    "/documents/:path*",
    "/profile/:path*",
  ],
};
