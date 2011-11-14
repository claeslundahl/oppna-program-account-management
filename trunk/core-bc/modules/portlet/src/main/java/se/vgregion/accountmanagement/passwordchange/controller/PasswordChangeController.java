/**
 * Copyright 2010 Västra Götalandsregionen
 *
 *   This library is free software; you can redistribute it and/or modify
 *   it under the terms of version 2.1 of the GNU Lesser General Public
 *   License as published by the Free Software Foundation.
 *
 *   This library is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU Lesser General Public License for more details.
 *
 *   You should have received a copy of the GNU Lesser General Public
 *   License along with this library; if not, write to the
 *   Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *   Boston, MA 02111-1307  USA
 *
 */

package se.vgregion.accountmanagement.passwordchange.controller;

import com.liferay.portal.kernel.exception.SystemException;
import com.liferay.portal.kernel.util.WebKeys;
import com.liferay.portal.model.UserGroup;
import com.liferay.portal.theme.ThemeDisplay;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.portlet.bind.annotation.ActionMapping;
import org.springframework.web.portlet.bind.annotation.RenderMapping;
import se.vgregion.accountmanagement.passwordchange.PasswordChangeException;
import se.vgregion.ldapservice.SimpleLdapServiceImpl;
import se.vgregion.ldapservice.SimpleLdapUser;

import javax.naming.NamingException;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.ModificationItem;
import javax.portlet.ActionRequest;
import javax.portlet.ActionResponse;
import javax.portlet.PortletRequest;
import javax.portlet.RenderRequest;
import javax.xml.bind.DatatypeConverter;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * @author Patrik Bergström
 */

@Controller
@RequestMapping("VIEW")
public class PasswordChangeController {

    @Autowired
    private SimpleLdapServiceImpl simpleLdapService;

    @Value("${changepassword.messagebus.destination}")
    private String messagebusDestination;
    @Value("${basic_authentication.username}")
    private String basicAuthUsername;
    @Value("${basic_authentication.password}")
    private String basicAuthPassword;
    @Value("${dominoUsersUserGroupName}")
    private String dominoUsersUserGroupName;


    public PasswordChangeController() {

    }

    public PasswordChangeController(SimpleLdapServiceImpl simpleLdapService) {
        this.simpleLdapService = simpleLdapService;
    }

    public void setDominoUsersUserGroupName(String dominoUsersUserGroupName) {
        this.dominoUsersUserGroupName = dominoUsersUserGroupName;
    }

    @RenderMapping
    public String showPasswordChangeForm(RenderRequest request, Model model) throws PasswordChangeException {

        boolean isDomino = isDominoUser(request);

        if (isDomino) {
            return "dominoNotImplemented";
        }

        //lookup user's vgr id
        String screenName = lookupScreenName(request);
        if (screenName != null) {
            model.addAttribute("vgrId", screenName);
        } else {
            model.addAttribute("errorMessage", "Kunde inte hitta ditt vgr-id.");
        }


        return "passwordChangeForm";
    }

    private String lookupScreenName(PortletRequest request) {
        String screenName;
        ThemeDisplay themeDisplay = (ThemeDisplay) request.getAttribute(WebKeys.THEME_DISPLAY);
        if (themeDisplay.getUser() != null) {
            screenName = themeDisplay.getUser().getScreenName();
            if (screenName != null) {
                return screenName;
            } else {
                return null;
            }
        } else {
            return null;
        }
    }

    @RenderMapping(params = "success")
    public String showSuccessPage() {
        return "success";
    }

    @RenderMapping(params = "failure=dominoNotImplemented")
    public String showDominoNotImplementedPage() {
        return "dominoNotImplemented";
    }

    @ActionMapping(params = "action=changePassword")
    public void changePassword(ActionRequest request, ActionResponse response, Model model)
            throws PasswordChangeException {
        String password = request.getParameter("password");
        String passwordConfirm = request.getParameter("passwordConfirm");

        try {
            //lookup user's vgr id
            String screenName = lookupScreenName(request);

            //validate
            validatePassword(password, passwordConfirm);

            boolean isDomino = isDominoUser(request);

            if (isDomino) {
                response.setRenderParameter("failure", "dominoNotImplemented");
            } else {
                //no domino -> continue with setting password in LDAP only, directly
                setPasswordInLdap(screenName, password);
                verifyPasswordWasModified(screenName, encryptWithSha(password)); //temporary? change when we
                // implement domino password change
                response.setRenderParameter("success", "success");

            }

        } catch (PasswordChangeException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
        }
    }

    protected void validatePassword(String password, String passwordConfirm) throws PasswordChangeException {
        if (password != null) {
            if (!password.equals(passwordConfirm)) {
                throw new PasswordChangeException("Lösenorden matchar inte.");
            }
            //validate strength
            final int i = 6;
            if (password.length() < i) {
                throw new PasswordChangeException("Lösenordet måste vara minst 6 tecken.");
            }
            if (!password.matches("[a-zA-Z0-9]*")) {
                throw new PasswordChangeException("Lösenordet får bara innehålla bokstäver och siffror");
            }
            if (!(password.matches(".*[a-zA-Z]+.*") && password.matches(".*[0-9]+.*"))) {
                throw new PasswordChangeException("Lösenordet måste innehålla både bokstäver och siffror");
            }
        } else {
            throw new PasswordChangeException("Fyll i lösenord.");
        }
    }

    boolean isDominoUser(PortletRequest request) throws PasswordChangeException {
        ThemeDisplay themeDisplay = (ThemeDisplay) request.getAttribute(WebKeys.THEME_DISPLAY);
        try {
            List<UserGroup> userGroups = themeDisplay.getUser().getUserGroups();
            if (userGroups != null) {
                for (UserGroup userGroup : userGroups) {
                    String userGroupName = userGroup.getName();
                    if (userGroupName != null && userGroupName.toLowerCase()
                            .contains(dominoUsersUserGroupName.toLowerCase())) {
                        return true;
                    }
                }
            }
            //no domino role found
            return false;
        } catch (SystemException e) {
            throw new PasswordChangeException(e);
        }
    }

    protected void setPasswordInLdap(String uid, String password) throws PasswordChangeException {
        SimpleLdapUser ldapUser = (SimpleLdapUser) simpleLdapService.getLdapUserByUid(uid);

        if (ldapUser == null) {
            throw new PasswordChangeException("Din användare kunde inte hittas i katalogservern.");
        }

        String encPassword = encryptWithSha(password);

        simpleLdapService.getLdapTemplate().getLdapOperations().modifyAttributes(
                ldapUser.getDn(), new ModificationItem[]{new ModificationItem(DirContext.REPLACE_ATTRIBUTE,
                new BasicAttribute("userPassword", encPassword))});
    }

    protected String encryptWithSha(String password) {
        String encPassword = null;
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA");
            byte[] digest = sha.digest(password.getBytes("UTF-8"));
            encPassword = "{SHA}" + DatatypeConverter.printBase64Binary(digest);
        } catch (UnsupportedEncodingException e) {
            //won't happen
            e.printStackTrace();
        } catch (NoSuchAlgorithmException e) {
            //won't happen
            e.printStackTrace();
        }
        return encPassword;
    }

    protected void verifyPasswordWasModified(String uid, String encPassword) throws PasswordChangeException {
        SimpleLdapUser ldapUser;//verify
        ldapUser = (SimpleLdapUser) simpleLdapService.getLdapUserByUid(uid);
        byte[] userPassword;
        try {
            userPassword = (byte[]) ldapUser.getAttributes(new String[]{"userPassword"}).get("userPassword").get();
            String passwordToVerify = new String(userPassword, "UTF-8");
            if (!encPassword.equals(passwordToVerify)) {
                throw new PasswordChangeException("Lyckades inte byta lösenord.");
            }
        } catch (NamingException e) {
            throw new PasswordChangeException(e);
        } catch (UnsupportedEncodingException e) {
            //won't happen
            e.printStackTrace();
        }
    }
}