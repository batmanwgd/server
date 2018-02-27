package gov.osti.security;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Calendar;
import java.util.Date;

import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.NewCookie;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

public class DOECodeCrypt {

    private static final SecureRandom random = new SecureRandom();
    // set the TIME OUT value in MINUTES
    private static final int TIMEOUT_IN_MINUTES = 45;

    public static String nextRandomString() {
        return new BigInteger(130, random).toString(32);
    }
	
    public static String nextUniqueString() {
    	return nextRandomString() + "x" +  System.currentTimeMillis(); 
    }
	public static String generateLoginJWT(String userID, String xsrfToken) {
		
		Calendar c = Calendar.getInstance();
		c.add(Calendar.MINUTE, TIMEOUT_IN_MINUTES);
	    return Jwts.builder().setIssuer("doecode").claim("xsrfToken", xsrfToken).setSubject(userID).setExpiration(c.getTime()).signWith(SignatureAlgorithm.HS256,"Secret").compact();
	       
		
	}
	
        /**
         * Generate a CONFIRMATION CODE JWT token.  Token no longer expires.
         * 
         * @param confirmationCode the text confirmation code value
         * @param email the email address/user account
         * @return a JWT based on the confirmation code
         */
	public static String generateConfirmationJwt(String confirmationCode, String email) {
		Calendar c = Calendar.getInstance();
		c.add(Calendar.MINUTE, TIMEOUT_IN_MINUTES);
	    return Jwts.builder().setIssuer("doecode").setId(confirmationCode).setSubject(email).signWith(SignatureAlgorithm.HS256,"Secret").compact();
	}
	
	public static Claims parseJWT(String jwt) {
		Claims claims = Jwts.parser().setSigningKey("Secret").parseClaimsJws(jwt).getBody();
		return claims;
	}
	
	
	public static NewCookie generateNewCookie(String accessToken) {
		Calendar c = Calendar.getInstance();
		c.add(Calendar.MINUTE, TIMEOUT_IN_MINUTES);
		
		Cookie cookie = new Cookie("accessToken", accessToken, "/", null);

		return new NewCookie(cookie, "", 60*TIMEOUT_IN_MINUTES, c.getTime(),false,true);
	}
        
        public static NewCookie invalidateCookie() {
            Cookie cookie = new Cookie("accessToken", "", "/", null);
            
            return new NewCookie(cookie, "", 0, new Date(), false, true);
            
        }
}
