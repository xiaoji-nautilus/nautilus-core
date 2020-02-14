package com.xiaoji.duan.nautilus.core.github;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

public class GitHubUtils {
	public static PrivateKey get(String filename) throws Exception {
		Path path = Paths.get(filename);
		byte[] keyBytes = Files.readAllBytes(path);

		PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
		KeyFactory kf = KeyFactory.getInstance("RSA");
		return kf.generatePrivate(spec);
	}

	public static String createJWT(String githubAppId, String keypath, long ttlMillis) throws Exception {
		// The JWT signature algorithm we will be using to sign the token
		SignatureAlgorithm signatureAlgorithm = SignatureAlgorithm.RS256;

		long nowMillis = System.currentTimeMillis();
		Date now = new Date(nowMillis);

		// We will sign our JWT with our private key
//		Key signingKey = get("/usr/verticles/nautilus-cs.2020-01-19.private-key.der");
		Key signingKey = get(keypath);

		// Let's set the JWT Claims
		JwtBuilder builder = Jwts.builder().setIssuedAt(now).setIssuer(githubAppId).signWith(signingKey,
				signatureAlgorithm);

		// if it has been specified, let's add the expiration
		if (ttlMillis > 0) {
			long expMillis = nowMillis + ttlMillis;
			Date exp = new Date(expMillis);
			builder.setExpiration(exp);
		}

		// Builds the JWT and serializes it to a compact, URL-safe string
		return builder.compact();
	}
}
