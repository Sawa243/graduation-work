using System.Security.Claims;
using Microsoft.IdentityModel.JsonWebTokens;

namespace ServicePult.Services;

public static class ClaimsExtensions
{
    /// <summary>id сотрудника из токена доступа (утверждение sub).</summary>
    public static int EmployeeId(this ClaimsPrincipal user) =>
        int.Parse(user.FindFirstValue(JwtRegisteredClaimNames.Sub)
                  ?? throw new InvalidOperationException("В токене нет sub"));
}
