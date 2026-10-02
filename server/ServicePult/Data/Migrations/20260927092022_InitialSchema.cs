using System;
using Microsoft.EntityFrameworkCore.Migrations;
using Npgsql.EntityFrameworkCore.PostgreSQL.Metadata;

#nullable disable

namespace ServicePult.Data.Migrations
{
    /// <inheritdoc />
    public partial class InitialSchema : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "calendar_day",
                columns: table => new
                {
                    day = table.Column<DateOnly>(type: "date", nullable: false),
                    is_working = table.Column<bool>(type: "boolean", nullable: false),
                    note = table.Column<string>(type: "character varying(60)", maxLength: 60, nullable: true),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_calendar_day", x => x.day);
                });

            migrationBuilder.CreateTable(
                name: "customer",
                columns: table => new
                {
                    id = table.Column<int>(type: "integer", nullable: false)
                        .Annotation("Npgsql:ValueGenerationStrategy", NpgsqlValueGenerationStrategy.IdentityByDefaultColumn),
                    code = table.Column<string>(type: "char(4)", nullable: false),
                    name = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false),
                    inn = table.Column<string>(type: "character varying(12)", maxLength: 12, nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_customer", x => x.id);
                });

            migrationBuilder.CreateTable(
                name: "employee",
                columns: table => new
                {
                    id = table.Column<int>(type: "integer", nullable: false)
                        .Annotation("Npgsql:ValueGenerationStrategy", NpgsqlValueGenerationStrategy.IdentityByDefaultColumn),
                    tab_no = table.Column<string>(type: "char(5)", nullable: false),
                    full_name = table.Column<string>(type: "character varying(100)", maxLength: 100, nullable: false),
                    position = table.Column<string>(type: "character varying(60)", maxLength: 60, nullable: false),
                    role = table.Column<short>(type: "smallint", nullable: false),
                    phone = table.Column<string>(type: "character varying(20)", maxLength: 20, nullable: true),
                    login = table.Column<string>(type: "character varying(40)", maxLength: 40, nullable: false),
                    pass_hash = table.Column<string>(type: "character varying(128)", maxLength: 128, nullable: false),
                    blocked = table.Column<bool>(type: "boolean", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_employee", x => x.id);
                });

            migrationBuilder.CreateTable(
                name: "sla_level",
                columns: table => new
                {
                    id = table.Column<int>(type: "integer", nullable: false)
                        .Annotation("Npgsql:ValueGenerationStrategy", NpgsqlValueGenerationStrategy.IdentityByDefaultColumn),
                    code = table.Column<string>(type: "char(5)", nullable: false),
                    name = table.Column<string>(type: "character varying(40)", maxLength: 40, nullable: false),
                    reaction_min = table.Column<int>(type: "integer", nullable: false),
                    resolution_min = table.Column<int>(type: "integer", nullable: false),
                    mode = table.Column<short>(type: "smallint", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_sla_level", x => x.id);
                });

            migrationBuilder.CreateTable(
                name: "work_type",
                columns: table => new
                {
                    id = table.Column<int>(type: "integer", nullable: false)
                        .Annotation("Npgsql:ValueGenerationStrategy", NpgsqlValueGenerationStrategy.IdentityByDefaultColumn),
                    code = table.Column<string>(type: "char(3)", nullable: false),
                    name = table.Column<string>(type: "character varying(120)", maxLength: 120, nullable: false),
                    norm_min = table.Column<int>(type: "integer", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_work_type", x => x.id);
                });

            migrationBuilder.CreateTable(
                name: "contract",
                columns: table => new
                {
                    id = table.Column<int>(type: "integer", nullable: false)
                        .Annotation("Npgsql:ValueGenerationStrategy", NpgsqlValueGenerationStrategy.IdentityByDefaultColumn),
                    number = table.Column<string>(type: "char(11)", nullable: false),
                    customer_id = table.Column<int>(type: "integer", nullable: false),
                    date_from = table.Column<DateOnly>(type: "date", nullable: false),
                    date_to = table.Column<DateOnly>(type: "date", nullable: true),
                    max_sla = table.Column<short>(type: "smallint", nullable: false),
                    e_sign_allowed = table.Column<bool>(type: "boolean", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_contract", x => x.id);
                    table.ForeignKey(
                        name: "FK_contract_customer_customer_id",
                        column: x => x.customer_id,
                        principalTable: "customer",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateTable(
                name: "site",
                columns: table => new
                {
                    id = table.Column<int>(type: "integer", nullable: false)
                        .Annotation("Npgsql:ValueGenerationStrategy", NpgsqlValueGenerationStrategy.IdentityByDefaultColumn),
                    code = table.Column<string>(type: "char(7)", nullable: false),
                    customer_id = table.Column<int>(type: "integer", nullable: false),
                    address = table.Column<string>(type: "character varying(300)", maxLength: 300, nullable: false),
                    contact_name = table.Column<string>(type: "character varying(100)", maxLength: 100, nullable: true),
                    contact_phone = table.Column<string>(type: "character varying(20)", maxLength: 20, nullable: true),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_site", x => x.id);
                    table.ForeignKey(
                        name: "FK_site_customer_customer_id",
                        column: x => x.customer_id,
                        principalTable: "customer",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateTable(
                name: "checklist_item",
                columns: table => new
                {
                    id = table.Column<int>(type: "integer", nullable: false)
                        .Annotation("Npgsql:ValueGenerationStrategy", NpgsqlValueGenerationStrategy.IdentityByDefaultColumn),
                    work_type_id = table.Column<int>(type: "integer", nullable: false),
                    pos = table.Column<short>(type: "smallint", nullable: false),
                    text = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false),
                    required = table.Column<bool>(type: "boolean", nullable: false),
                    need_photo = table.Column<bool>(type: "boolean", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_checklist_item", x => x.id);
                    table.ForeignKey(
                        name: "FK_checklist_item_work_type_work_type_id",
                        column: x => x.work_type_id,
                        principalTable: "work_type",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateTable(
                name: "equipment",
                columns: table => new
                {
                    id = table.Column<int>(type: "integer", nullable: false)
                        .Annotation("Npgsql:ValueGenerationStrategy", NpgsqlValueGenerationStrategy.IdentityByDefaultColumn),
                    inv_code = table.Column<string>(type: "char(12)", nullable: false),
                    site_id = table.Column<int>(type: "integer", nullable: false),
                    vendor = table.Column<string>(type: "character varying(60)", maxLength: 60, nullable: false),
                    model = table.Column<string>(type: "character varying(100)", maxLength: 100, nullable: false),
                    serial_no = table.Column<string>(type: "character varying(60)", maxLength: 60, nullable: true),
                    warranty_to = table.Column<DateOnly>(type: "date", nullable: true),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_equipment", x => x.id);
                    table.ForeignKey(
                        name: "FK_equipment_site_site_id",
                        column: x => x.site_id,
                        principalTable: "site",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateTable(
                name: "request",
                columns: table => new
                {
                    id = table.Column<int>(type: "integer", nullable: false)
                        .Annotation("Npgsql:ValueGenerationStrategy", NpgsqlValueGenerationStrategy.IdentityByDefaultColumn),
                    number = table.Column<string>(type: "char(11)", nullable: false),
                    contract_id = table.Column<int>(type: "integer", nullable: false),
                    site_id = table.Column<int>(type: "integer", nullable: false),
                    equipment_id = table.Column<int>(type: "integer", nullable: true),
                    work_type_id = table.Column<int>(type: "integer", nullable: false),
                    sla_level_id = table.Column<int>(type: "integer", nullable: false),
                    dispatcher_id = table.Column<int>(type: "integer", nullable: false),
                    engineer_id = table.Column<int>(type: "integer", nullable: true),
                    parent_id = table.Column<int>(type: "integer", nullable: true),
                    description = table.Column<string>(type: "text", nullable: false),
                    registered_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    reaction_due = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    resolution_due = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    arrived_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                    completed_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                    closed_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                    status = table.Column<short>(type: "smallint", nullable: false),
                    scanned_code = table.Column<string>(type: "character varying(100)", maxLength: 100, nullable: true),
                    result_text = table.Column<string>(type: "text", nullable: true),
                    cancel_reason = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: true),
                    time_trusted = table.Column<bool>(type: "boolean", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_request", x => x.id);
                    table.ForeignKey(
                        name: "FK_request_contract_contract_id",
                        column: x => x.contract_id,
                        principalTable: "contract",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Restrict);
                    table.ForeignKey(
                        name: "FK_request_employee_dispatcher_id",
                        column: x => x.dispatcher_id,
                        principalTable: "employee",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Restrict);
                    table.ForeignKey(
                        name: "FK_request_employee_engineer_id",
                        column: x => x.engineer_id,
                        principalTable: "employee",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Restrict);
                    table.ForeignKey(
                        name: "FK_request_equipment_equipment_id",
                        column: x => x.equipment_id,
                        principalTable: "equipment",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Restrict);
                    table.ForeignKey(
                        name: "FK_request_request_parent_id",
                        column: x => x.parent_id,
                        principalTable: "request",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Restrict);
                    table.ForeignKey(
                        name: "FK_request_site_site_id",
                        column: x => x.site_id,
                        principalTable: "site",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Restrict);
                    table.ForeignKey(
                        name: "FK_request_sla_level_sla_level_id",
                        column: x => x.sla_level_id,
                        principalTable: "sla_level",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Restrict);
                    table.ForeignKey(
                        name: "FK_request_work_type_work_type_id",
                        column: x => x.work_type_id,
                        principalTable: "work_type",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateTable(
                name: "act",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    request_id = table.Column<int>(type: "integer", nullable: false),
                    number = table.Column<string>(type: "char(11)", nullable: false),
                    act_date = table.Column<DateOnly>(type: "date", nullable: false),
                    engineer_id = table.Column<int>(type: "integer", nullable: false),
                    sign_mode = table.Column<short>(type: "smallint", nullable: false),
                    signer_name = table.Column<string>(type: "character varying(100)", maxLength: 100, nullable: true),
                    signer_position = table.Column<string>(type: "character varying(100)", maxLength: 100, nullable: true),
                    signature_file = table.Column<string>(type: "character varying(100)", maxLength: 100, nullable: true),
                    pdf_file = table.Column<string>(type: "character varying(100)", maxLength: 100, nullable: false),
                    signed_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                    sha256 = table.Column<string>(type: "char(64)", nullable: false),
                    work_min = table.Column<int>(type: "integer", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_act", x => x.id);
                    table.ForeignKey(
                        name: "FK_act_employee_engineer_id",
                        column: x => x.engineer_id,
                        principalTable: "employee",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Restrict);
                    table.ForeignKey(
                        name: "FK_act_request_request_id",
                        column: x => x.request_id,
                        principalTable: "request",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateTable(
                name: "checklist_result",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    request_id = table.Column<int>(type: "integer", nullable: false),
                    checklist_item_id = table.Column<int>(type: "integer", nullable: false),
                    done = table.Column<bool>(type: "boolean", nullable: false),
                    comment = table.Column<string>(type: "character varying(300)", maxLength: 300, nullable: true),
                    done_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_checklist_result", x => x.id);
                    table.ForeignKey(
                        name: "FK_checklist_result_checklist_item_checklist_item_id",
                        column: x => x.checklist_item_id,
                        principalTable: "checklist_item",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Restrict);
                    table.ForeignKey(
                        name: "FK_checklist_result_request_request_id",
                        column: x => x.request_id,
                        principalTable: "request",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateTable(
                name: "photo",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    request_id = table.Column<int>(type: "integer", nullable: false),
                    checklist_item_id = table.Column<int>(type: "integer", nullable: true),
                    kind = table.Column<short>(type: "smallint", nullable: false),
                    file_name = table.Column<string>(type: "character varying(100)", maxLength: 100, nullable: false),
                    taken_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    sha256 = table.Column<string>(type: "char(64)", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_photo", x => x.id);
                    table.ForeignKey(
                        name: "FK_photo_checklist_item_checklist_item_id",
                        column: x => x.checklist_item_id,
                        principalTable: "checklist_item",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Restrict);
                    table.ForeignKey(
                        name: "FK_photo_request_request_id",
                        column: x => x.request_id,
                        principalTable: "request",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateTable(
                name: "work_log",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    request_id = table.Column<int>(type: "integer", nullable: false),
                    employee_id = table.Column<int>(type: "integer", nullable: false),
                    kind = table.Column<short>(type: "smallint", nullable: false),
                    started_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    ended_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: true),
                    time_trusted = table.Column<bool>(type: "boolean", nullable: false),
                    updated_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_work_log", x => x.id);
                    table.ForeignKey(
                        name: "FK_work_log_employee_employee_id",
                        column: x => x.employee_id,
                        principalTable: "employee",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Restrict);
                    table.ForeignKey(
                        name: "FK_work_log_request_request_id",
                        column: x => x.request_id,
                        principalTable: "request",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateIndex(
                name: "IX_act_engineer_id",
                table: "act",
                column: "engineer_id");

            migrationBuilder.CreateIndex(
                name: "IX_act_number",
                table: "act",
                column: "number",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_act_request_id",
                table: "act",
                column: "request_id",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_checklist_item_work_type_id",
                table: "checklist_item",
                column: "work_type_id");

            migrationBuilder.CreateIndex(
                name: "IX_checklist_result_checklist_item_id",
                table: "checklist_result",
                column: "checklist_item_id");

            migrationBuilder.CreateIndex(
                name: "IX_checklist_result_request_id_checklist_item_id",
                table: "checklist_result",
                columns: new[] { "request_id", "checklist_item_id" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_contract_customer_id",
                table: "contract",
                column: "customer_id");

            migrationBuilder.CreateIndex(
                name: "IX_contract_number",
                table: "contract",
                column: "number",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_customer_code",
                table: "customer",
                column: "code",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_employee_login",
                table: "employee",
                column: "login",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_employee_tab_no",
                table: "employee",
                column: "tab_no",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_equipment_inv_code",
                table: "equipment",
                column: "inv_code",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_equipment_site_id",
                table: "equipment",
                column: "site_id");

            migrationBuilder.CreateIndex(
                name: "IX_photo_checklist_item_id",
                table: "photo",
                column: "checklist_item_id");

            migrationBuilder.CreateIndex(
                name: "IX_photo_request_id",
                table: "photo",
                column: "request_id");

            migrationBuilder.CreateIndex(
                name: "IX_request_contract_id",
                table: "request",
                column: "contract_id");

            migrationBuilder.CreateIndex(
                name: "IX_request_dispatcher_id",
                table: "request",
                column: "dispatcher_id");

            migrationBuilder.CreateIndex(
                name: "IX_request_engineer_id",
                table: "request",
                column: "engineer_id");

            migrationBuilder.CreateIndex(
                name: "IX_request_equipment_id",
                table: "request",
                column: "equipment_id");

            migrationBuilder.CreateIndex(
                name: "IX_request_number",
                table: "request",
                column: "number",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_request_parent_id",
                table: "request",
                column: "parent_id");

            migrationBuilder.CreateIndex(
                name: "IX_request_site_id",
                table: "request",
                column: "site_id");

            migrationBuilder.CreateIndex(
                name: "IX_request_sla_level_id",
                table: "request",
                column: "sla_level_id");

            migrationBuilder.CreateIndex(
                name: "IX_request_work_type_id",
                table: "request",
                column: "work_type_id");

            migrationBuilder.CreateIndex(
                name: "IX_site_code",
                table: "site",
                column: "code",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_site_customer_id",
                table: "site",
                column: "customer_id");

            migrationBuilder.CreateIndex(
                name: "IX_sla_level_code",
                table: "sla_level",
                column: "code",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_work_log_employee_id",
                table: "work_log",
                column: "employee_id");

            migrationBuilder.CreateIndex(
                name: "IX_work_log_request_id",
                table: "work_log",
                column: "request_id");

            migrationBuilder.CreateIndex(
                name: "IX_work_type_code",
                table: "work_type",
                column: "code",
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "act");

            migrationBuilder.DropTable(
                name: "calendar_day");

            migrationBuilder.DropTable(
                name: "checklist_result");

            migrationBuilder.DropTable(
                name: "photo");

            migrationBuilder.DropTable(
                name: "work_log");

            migrationBuilder.DropTable(
                name: "checklist_item");

            migrationBuilder.DropTable(
                name: "request");

            migrationBuilder.DropTable(
                name: "contract");

            migrationBuilder.DropTable(
                name: "employee");

            migrationBuilder.DropTable(
                name: "equipment");

            migrationBuilder.DropTable(
                name: "sla_level");

            migrationBuilder.DropTable(
                name: "work_type");

            migrationBuilder.DropTable(
                name: "site");

            migrationBuilder.DropTable(
                name: "customer");
        }
    }
}
