//! `dnote-server` – self-hosted DNote.
//!
//! Environment:
//! * `DNOTE_TOKEN` (required) – bearer token clients must send
//! * `DNOTE_DATA`  (default `./data`) – SQLite DB + `notes/` folder
//! * `DNOTE_ADDR`  (default `0.0.0.0:8787`)
//! * `DNOTE_WEB`   (default `./web/dist`) – built web app, served at `/`

mod store;

use axum::body::Bytes;
use axum::extract::{DefaultBodyLimit, State};
use axum::http::{header, HeaderMap, Method, StatusCode, Uri};
use axum::response::{IntoResponse, Response};
use axum::routing::any;
use axum::Router;
use dnote_core::api;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};
use store::FsStore;
use tower_http::compression::CompressionLayer;
use tower_http::cors::CorsLayer;
use tower_http::services::{ServeDir, ServeFile};

struct App {
    store: FsStore,
    config: api::Config,
}

fn env_or(key: &str, default: &str) -> String {
    std::env::var(key)
        .ok()
        .filter(|v| !v.is_empty())
        .unwrap_or_else(|| default.to_string())
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            std::env::var("RUST_LOG").unwrap_or_else(|_| "info,tower_http=info".into()),
        )
        .init();

    let token = std::env::var("DNOTE_TOKEN").unwrap_or_default();
    if token.len() < 16 {
        eprintln!("DNOTE_TOKEN must be set to a random string of at least 16 characters (try: openssl rand -hex 24)");
        std::process::exit(2);
    }
    let data = PathBuf::from(env_or("DNOTE_DATA", "./data"));
    let addr = env_or("DNOTE_ADDR", "0.0.0.0:8787");
    let web = PathBuf::from(env_or("DNOTE_WEB", "./web/dist"));

    let store = FsStore::open(&data).unwrap_or_else(|e| {
        eprintln!("failed to open data dir {}: {e}", data.display());
        std::process::exit(1);
    });
    let app = Arc::new(App {
        store,
        config: api::Config {
            token,
            backend: "self-hosted".into(),
        },
    });

    let spa = ServeDir::new(&web).fallback(ServeFile::new(web.join("index.html")));
    let router = Router::new()
        .route("/api/{*rest}", any(handle_api))
        .layer(DefaultBodyLimit::max(dnote_core::protocol::MAX_BODY_BYTES))
        .layer(CorsLayer::permissive())
        .fallback_service(spa)
        .layer(CompressionLayer::new())
        .with_state(app);

    let listener = tokio::net::TcpListener::bind(&addr)
        .await
        .unwrap_or_else(|e| {
            eprintln!("cannot bind {addr}: {e}");
            std::process::exit(1);
        });
    tracing::info!(
        "dnote listening on http://{addr} (data: {}, web: {})",
        data.display(),
        web.display()
    );
    axum::serve(listener, router)
        .with_graceful_shutdown(async {
            let _ = tokio::signal::ctrl_c().await;
        })
        .await
        .unwrap();
}

async fn handle_api(
    State(app): State<Arc<App>>,
    method: Method,
    uri: Uri,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    let req = api::Request {
        method: method.to_string(),
        path: uri.path().to_string(),
        query: uri.query().unwrap_or("").to_string(),
        authorization: headers
            .get(header::AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            .map(str::to_string),
        body: body.to_vec(),
        now_ms: SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis() as i64)
            .unwrap_or(0),
    };
    // The core handler is runtime-agnostic (it also runs single-threaded inside a
    // Cloudflare Worker); storage here is blocking SQLite + file I/O, so run it on
    // the blocking pool.
    let result = tokio::task::spawn_blocking(move || {
        pollster::block_on(api::handle(&app.store, &app.config, req))
    })
    .await;
    match result {
        Ok(resp) => (
            StatusCode::from_u16(resp.status).unwrap_or(StatusCode::INTERNAL_SERVER_ERROR),
            [(header::CONTENT_TYPE, api::Response::CONTENT_TYPE)],
            resp.body,
        )
            .into_response(),
        Err(_) => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
}
