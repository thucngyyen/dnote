//! Cloudflare Workers adapter for the DNote API.
//!
//! * D1 runs exactly the same SQL as the self-hosted server ([`dnote_core::sql`]).
//! * R2 stores note bodies content-addressed (`blobs/<sha256>`), so uploads are
//!   idempotent and a losing concurrent write can never clobber the winner's body.
//! * Static assets (the web app) are served by Workers Assets; only `/api/*` reaches this code.

use dnote_core::api::{self, meta_from_row, Commit, CommitOutcome, Store, StoreError};
use dnote_core::protocol::FileMeta;
use dnote_core::sql;
use serde::Deserialize;
use worker::wasm_bindgen::JsValue;
use worker::*;

#[derive(Deserialize)]
struct Row {
    path: String,
    version: i64,
    hash: String,
    size: i64,
    deleted: i64,
    seq: i64,
    updated_at: i64,
}

impl From<Row> for FileMeta {
    fn from(r: Row) -> Self {
        meta_from_row(
            r.path,
            r.version,
            r.hash,
            r.size,
            r.deleted,
            r.seq,
            r.updated_at,
        )
    }
}

struct CloudflareStore {
    db: D1Database,
    bucket: Bucket,
}

fn err<E: std::fmt::Display>(e: E) -> StoreError {
    e.to_string()
}

fn num(v: i64) -> JsValue {
    JsValue::from_f64(v as f64)
}

fn blob_key(hash: &str) -> String {
    format!("blobs/{hash}")
}

impl CloudflareStore {
    async fn read_meta(&self, path: &str) -> std::result::Result<Option<FileMeta>, StoreError> {
        let stmt = self
            .db
            .prepare(sql::READ_META)
            .bind(&[path.into()])
            .map_err(err)?;
        Ok(stmt.first::<Row>(None).await.map_err(err)?.map(Into::into))
    }
}

impl Store for CloudflareStore {
    async fn changes_since(
        &self,
        since: i64,
        limit: i64,
    ) -> std::result::Result<Vec<FileMeta>, StoreError> {
        let stmt = self
            .db
            .prepare(sql::CHANGES)
            .bind(&[num(since), num(limit)])
            .map_err(err)?;
        let rows: Vec<Row> = stmt.all().await.map_err(err)?.results().map_err(err)?;
        Ok(rows.into_iter().map(Into::into).collect())
    }

    async fn meta(&self, path: &str) -> std::result::Result<Option<FileMeta>, StoreError> {
        self.read_meta(path).await
    }

    async fn content(&self, meta: &FileMeta) -> std::result::Result<Option<Vec<u8>>, StoreError> {
        let Some(object) = self
            .bucket
            .get(blob_key(&meta.hash))
            .execute()
            .await
            .map_err(err)?
        else {
            return Ok(None);
        };
        match object.body() {
            Some(body) => Ok(Some(body.bytes().await.map_err(err)?)),
            None => Ok(None),
        }
    }

    async fn commit(&self, c: Commit<'_>) -> std::result::Result<CommitOutcome, StoreError> {
        if let Some(text) = c.content {
            // Upload first: content-addressed, so this is safe even if the SQL write loses.
            self.bucket
                .put(blob_key(&c.hash), text.to_vec())
                .execute()
                .await
                .map_err(err)?;
        }
        let deleted = c.content.is_none() as i64;
        let stmt = self
            .db
            .prepare(sql::COMMIT)
            .bind(&[
                c.path.into(),
                c.hash.as_str().into(),
                num(c.size),
                num(deleted),
                num(c.now_ms),
                num(c.base_version),
            ])
            .map_err(err)?;
        match stmt.first::<Row>(None).await.map_err(err)? {
            Some(row) => Ok(CommitOutcome::Committed(row.into())),
            None => Ok(CommitOutcome::Conflict(self.read_meta(c.path).await?)),
        }
    }
}

fn with_cors(mut resp: Response) -> Result<Response> {
    let h = resp.headers_mut();
    h.set("Access-Control-Allow-Origin", "*")?;
    h.set("Access-Control-Allow-Methods", "GET, PUT, DELETE, OPTIONS")?;
    h.set(
        "Access-Control-Allow-Headers",
        "Authorization, Content-Type",
    )?;
    h.set("Access-Control-Max-Age", "86400")?;
    Ok(resp)
}

#[event(fetch)]
async fn fetch(mut req: Request, env: Env, _ctx: Context) -> Result<Response> {
    if req.method() == Method::Options {
        return with_cors(Response::empty()?.with_status(204));
    }
    let url = req.url()?;
    let method = req.method().to_string();
    let authorization = req.headers().get("Authorization")?;
    let body = match req.method() {
        Method::Put | Method::Post => req.bytes().await?,
        _ => Vec::new(),
    };
    let token = env
        .secret("DNOTE_TOKEN")
        .map(|s| s.to_string())
        .unwrap_or_default();
    let store = CloudflareStore {
        db: env.d1("DB")?,
        bucket: env.bucket("NOTES")?,
    };
    let config = api::Config {
        token,
        backend: "cloudflare".into(),
    };
    let resp = api::handle(
        &store,
        &config,
        api::Request {
            method,
            path: url.path().to_string(),
            query: url.query().unwrap_or("").to_string(),
            authorization,
            body,
            now_ms: Date::now().as_millis() as i64,
        },
    )
    .await;
    let mut out = Response::from_bytes(resp.body)?.with_status(resp.status);
    out.headers_mut()
        .set("Content-Type", api::Response::CONTENT_TYPE)?;
    with_cors(out)
}
