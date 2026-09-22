package com.mall.module.order.entity.vo;

import lombok.Getter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * An immutable snapshot of the policy clauses returned by the policy endpoint.
 *
 * <p>The fingerprint is SHA-256 over each returned clause's code, title, and text in catalog
 * order. It detects changes to this catalog, including a changed clause order. It does not
 * represent executable policy rules and does not refresh an agent index by itself.</p>
 */
@Getter
public final class PolicyCatalogVO {

    private final String fingerprint;
    private final List<PolicyClauseVO> clauses;

    private PolicyCatalogVO(String fingerprint, List<PolicyClauseVO> clauses) {
        this.fingerprint = fingerprint;
        this.clauses = clauses;
    }

    /**
     * Creates a catalog from an immutable list snapshot and derives its fingerprint from that
     * same snapshot.
     */
    public static PolicyCatalogVO of(List<PolicyClauseVO> clauses) {
        List<PolicyClauseVO> snapshot = List.copyOf(clauses);
        return new PolicyCatalogVO(fingerprintOf(snapshot), snapshot);
    }

    private static String fingerprintOf(List<PolicyClauseVO> clauses) {
        StringBuilder canonical = new StringBuilder();
        for (PolicyClauseVO clause : clauses) {
            canonical.append(clause.getCode()).append('\0')
                    .append(clause.getTitle()).append('\0')
                    .append(clause.getClauseText()).append('\1');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK SHA-256 implementation is unavailable", e);
        }
    }
}
