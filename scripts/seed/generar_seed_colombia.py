#!/usr/bin/env python3
"""Genera src/main/resources/seed/colombia.sql: datos de demostración de Fix&Rent en Colombia.

Todos los datos son ficticios y coherentes entre sí: cada trabajo nace de una cotización aceptada de
un técnico verificado de la especialidad, cada pago aprobado corresponde a un trabajo, cada
calificación a un trabajo terminado, y así. El script es determinista (misma semilla, mismo SQL).

Marcadores que reemplaza DemoDataSeedRunner al ejecutar el script:
  {{U:owner}} {{U:tenant}} {{U:fixer}} {{U:manager}} {{U:admin}}  -> UUID de las cuentas protagonistas
  {{T:-3600}}  -> TIMESTAMP WITH TIME ZONE de "ahora" más N segundos (negativo = pasado)
  {{D:-30}}    -> DATE de "hoy" más N días
  {{W:3:540}}  -> TIMESTAMP de "hoy" más N días a esa hora de reloj en Colombia (minutos desde medianoche)
Así los datos siempre se ven recientes, sin importar cuándo se cargue la base.

Uso: python3 scripts/seed/generar_seed_colombia.py
"""
import json
import math
import os
import random
import uuid

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RESOURCES = os.path.join(ROOT, "src", "main", "resources", "seed")
rng = random.Random(2026)
NAMESPACE = uuid.UUID("6f1c6b8a-0d3e-4f0a-9a5e-3c1b2d4e5f60")
DAY = 86400
HOUR = 3600


def uid(*parts):
    return str(uuid.uuid5(NAMESPACE, "/".join(str(p) for p in parts)))


def q(value):
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, (int, float)):
        return str(value)
    if isinstance(value, Raw):
        return value.sql
    return "'" + str(value).replace("'", "''") + "'"


class Raw:
    def __init__(self, sql):
        self.sql = sql


class BizTime(float):
    """Un instante relativo que debe caer en una hora de reloj de Colombia (visitas, citas de trabajo).

    Se comporta como segundos relativos a "ahora" para comparar y restar, suponiendo que la carga ocurre
    hacia el mediodía, pero se escribe como {{W:día:minuto}} para que la hora sea exacta al cargar.
    """

    def __new__(cls, day, minute):
        value = super().__new__(cls, day * DAY + minute * 60 - 12 * HOUR)
        value.day = day
        value.minute = minute
        return value


def T(seconds):
    if isinstance(seconds, BizTime):
        return Raw("{{W:%d:%d}}" % (seconds.day, seconds.minute))
    return Raw("{{T:%d}}" % int(seconds))


def D(days):
    return Raw("{{D:%d}}" % int(days))


def U(name):
    return Raw("{{U:%s}}" % name)


class Sql:
    def __init__(self):
        self.tables = {}
        self.order = []

    def add(self, table, row):
        if table not in self.tables:
            self.tables[table] = []
            self.order.append(table)
        self.tables[table].append(row)

    def render(self):
        out = ["-- Datos de demostración de Fix&Rent (Colombia). Generado por scripts/seed/generar_seed_colombia.py.",
               "-- No editar a mano: regenerar con el script."]
        for table in self.order:
            rows = self.tables[table]
            columns = list(rows[0].keys())
            for start in range(0, len(rows), 200):
                chunk = rows[start:start + 200]
                out.append(f"INSERT INTO {table} ({', '.join(columns)}) VALUES")
                values = [f"({', '.join(q(row[c]) for c in columns)})" for row in chunk]
                out.append(",\n".join(values) + ";")
        return "\n".join(out) + "\n"


sql = Sql()
catalog = json.load(open(os.path.join(RESOURCES, "images", "catalog.json")))
images = {}
for item in catalog:
    images.setdefault((item["category"], item["tag"]), []).append(item)
for name in ("cedula-prueba", "certificado-prueba"):
    path = os.path.join(RESOURCES, "images", "verificacion", name + ".jpg")
    images[("verificacion", name)] = [{"file": f"verificacion/{name}.jpg", "bytes": os.path.getsize(path)}]

# ---------------------------------------------------------------- personas
FIRST = ["Andrés", "Carolina", "Juan Camilo", "María Fernanda", "Santiago", "Valentina", "Sebastián", "Laura",
         "Daniel", "Natalia", "Felipe", "Paula", "Alejandro", "Daniela", "Camilo", "Juliana", "Jorge", "Catalina",
         "Luis Fernando", "Ana María", "Óscar", "Diana", "Ricardo", "Sandra", "Mauricio", "Liliana", "Julián",
         "Marcela", "Diego", "Paola", "Esteban", "Manuela", "Nicolás", "Isabella", "Hernán", "Gloria", "Fabián",
         "Claudia", "Wilson", "Yolanda", "Jhon Jairo", "Luz Marina", "Édgar", "Adriana", "Hugo", "Ángela", "Iván",
         "Mónica", "Germán", "Lorena", "Álvaro", "Beatriz", "Sergio", "Ximena", "Jairo", "Patricia", "Cristian",
         "Viviana", "Brayan", "Leidy"]
LAST = ["Gómez", "Rodríguez", "Martínez", "García", "López", "Hernández", "González", "Pérez", "Sánchez",
        "Ramírez", "Torres", "Díaz", "Restrepo", "Cárdenas", "Vargas", "Moreno", "Rojas", "Jiménez", "Castro",
        "Ortiz", "Ruiz", "Álvarez", "Mejía", "Ospina", "Zapata", "Arango", "Gutiérrez", "Salazar", "Valencia",
        "Quintero", "Cardona", "Muñoz", "Rincón", "Pineda", "Escobar", "Londoño", "Bermúdez", "Suárez", "Navarro",
        "Arias", "Parra", "Duque", "Patiño", "Caicedo", "Mosquera", "Palacios", "Montoya", "Henao", "Giraldo",
        "Agudelo"]
COMPANIES = ["Habitar Propiedad Raíz", "Arriendos del Valle", "Gestión Inmobiliaria Caribe",
             "Santander Propiedades", "Cafetera Inmobiliaria"]

used_names = set()


def person_name():
    while True:
        name = f"{rng.choice(FIRST)} {rng.choice(LAST)} {rng.choice(LAST)}"
        if name not in used_names:
            used_names.add(name)
            return name


def slug(text):
    table = str.maketrans("áéíóúÁÉÍÓÚñÑü", "aeiouAEIOUnNu")
    return "".join(ch for ch in text.translate(table).lower().replace(" ", ".") if ch.isalnum() or ch == ".")


users = {}  # key -> dict(id, name, roles)
PROTAGONISTS = {
    "owner": ("Carolina Restrepo Arango", ["OWNER"]),
    "tenant": ("Andrés Moreno Rincón", ["TENANT"]),
    "fixer": ("Jhon Fredy Cárdenas Ospina", ["FIXER"]),
    "manager": ("Inmobiliaria Cordillera", ["REAL_ESTATE_MANAGER", "OWNER"]),
    "admin": ("Soporte Fix&Rent", ["PLATFORM_ADMIN", "REAL_ESTATE_MANAGER", "OWNER"]),
}
for key, (name, roles) in PROTAGONISTS.items():
    users[key] = {"id": U(key), "name": name, "roles": roles, "protagonist": True}
    used_names.add(name)


def make_user(key, name, roles, created_days_ago):
    user_id = uid("user", key)
    users[key] = {"id": user_id, "name": name, "roles": roles, "protagonist": False}
    email = f"{slug(name)}@correo.fixrent.test"
    sql.add("users", {"id": user_id, "auth0_subject": f"seed|{key}", "email": email, "display_name": name,
                      "status": "ACTIVE", "created_at": T(-created_days_ago * DAY),
                      "updated_at": T(-created_days_ago * DAY)})
    for role in roles:
        sql.add("user_roles", {"user_id": user_id, "role": role})
    return users[key]


owners = [users["owner"]] + [make_user(f"owner-{i:02d}", person_name(), ["OWNER"], rng.randint(200, 700))
                             for i in range(1, 44)]
tenants = [users["tenant"]] + [make_user(f"tenant-{i:02d}", person_name(), ["TENANT"], rng.randint(60, 600))
                               for i in range(1, 56)]
managers = [users["manager"]] + [make_user(f"manager-{i:02d}", COMPANIES[i - 1], ["REAL_ESTATE_MANAGER", "OWNER"],
                                           rng.randint(300, 700)) for i in range(1, 6)]
admins = [users["admin"], make_user("admin-01", "Equipo de Verificación Fix&Rent", ["PLATFORM_ADMIN"], 650)]

# ---------------------------------------------------------------- ciudades
CITIES = {
    "Bogotá": (32, 38000, [("Chapinero Alto", 4.6458, -74.0596), ("Chicó", 4.6762, -74.0479),
                           ("Usaquén", 4.6951, -74.0310), ("Cedritos", 4.7232, -74.0402),
                           ("Teusaquillo", 4.6325, -74.0761), ("La Candelaria", 4.5965, -74.0730),
                           ("Salitre", 4.6493, -74.1047), ("Modelia", 4.6680, -74.1178),
                           ("Galerías", 4.6430, -74.0760), ("Colina Campestre", 4.7360, -74.0640),
                           ("Suba", 4.7410, -74.0830), ("Kennedy", 4.6290, -74.1550),
                           ("Rosales", 4.6560, -74.0510), ("Palermo", 4.6380, -74.0700),
                           ("Castilla", 4.6380, -74.1370)]),
    "Medellín": (18, 36000, [("El Poblado", 6.2088, -75.5672), ("Laureles", 6.2443, -75.5966),
                             ("Belén", 6.2310, -75.6020), ("Estadio", 6.2530, -75.5900),
                             ("Conquistadores", 6.2450, -75.5830), ("La América", 6.2520, -75.6120),
                             ("Provenza", 6.2085, -75.5650), ("Robledo", 6.2780, -75.5980),
                             ("Manila", 6.2130, -75.5730), ("Las Palmas", 6.2190, -75.5500)]),
    "Cali": (10, 28000, [("Granada", 3.4600, -76.5320), ("San Fernando", 3.4380, -76.5410),
                         ("Ciudad Jardín", 3.3640, -76.5320), ("El Peñón", 3.4500, -76.5410),
                         ("Santa Mónica", 3.4730, -76.5280), ("Valle del Lili", 3.3730, -76.5240),
                         ("Tequendama", 3.4230, -76.5430)]),
    "Barranquilla": (8, 30000, [("El Prado", 10.9930, -74.7980), ("Alto Prado", 11.0040, -74.8060),
                                ("Riomar", 11.0200, -74.8270), ("Villa Country", 11.0080, -74.8150),
                                ("Ciudad Jardín", 10.9880, -74.8100), ("Boston", 10.9960, -74.7900)]),
    "Cartagena": (7, 42000, [("Bocagrande", 10.3990, -75.5560), ("Getsemaní", 10.4210, -75.5470),
                             ("Manga", 10.4120, -75.5380), ("Castillogrande", 10.3890, -75.5490),
                             ("Crespo", 10.4460, -75.5130), ("Centro Histórico", 10.4240, -75.5500)]),
    "Bucaramanga": (5, 25000, [("Cabecera del Llano", 7.1170, -73.1080), ("Sotomayor", 7.1210, -73.1150),
                               ("San Alonso", 7.1230, -73.1190), ("Lagos del Cacique", 7.0990, -73.1040)]),
    "Envigado": (4, 37000, [("Zúñiga", 6.1830, -75.5850), ("La Magnolia", 6.1750, -75.5880)]),
    "Pereira": (4, 24000, [("Pinares", 4.8070, -75.6870), ("Álamos", 4.8040, -75.7200),
                           ("Circunvalar", 4.8100, -75.6930)]),
    "Manizales": (3, 22000, [("Palermo", 5.0590, -75.4900), ("Chipre", 5.0730, -75.5230),
                             ("La Francia", 5.0560, -75.4870)]),
    "Santa Marta": (4, 34000, [("El Rodadero", 11.2010, -74.2280), ("Bello Horizonte", 11.1830, -74.2230),
                               ("Centro Histórico", 11.2420, -74.2110)]),
    "Armenia": (2, 21000, [("Laureles", 4.5420, -75.6620), ("Norte", 4.5540, -75.6590)]),
    "Chía": (2, 30000, [("Fonquetá", 4.8790, -74.0430), ("Centro", 4.8620, -74.0580)]),
    "Villavicencio": (1, 20000, [("Barzal", 4.1460, -73.6360)]),
}
CITY_NAMES = list(CITIES)
CITY_WEIGHTS = [CITIES[c][0] for c in CITY_NAMES]
STRATUM_FACTOR = {1: 0.5, 2: 0.65, 3: 0.8, 4: 1.0, 5: 1.3, 6: 1.7}


def pick_city():
    return rng.choices(CITY_NAMES, weights=CITY_WEIGHTS)[0]


def address(city, kind):
    if city in ("Medellín", "Envigado") and rng.random() < 0.35:
        base = f"Carrera {rng.randint(25, 80)}{rng.choice(['', 'A', 'B'])} # {rng.randint(1, 40)} Sur-{rng.randint(10, 180)}"
    elif city == "Cali" and rng.random() < 0.4:
        base = f"Avenida {rng.randint(2, 9)}N # {rng.randint(10, 60)}N-{rng.randint(10, 99)}"
    elif city == "Cartagena" and rng.random() < 0.4:
        base = rng.choice(["Avenida San Martín", "Calle del Arsenal", "Avenida Pedro de Heredia", "Calle Real de Manga"]) \
               + f" # {rng.randint(2, 40)}-{rng.randint(10, 160)}"
    else:
        street = rng.choice(["Calle", "Carrera", "Calle", "Carrera", "Transversal", "Diagonal", "Avenida Calle"])
        base = f"{street} {rng.randint(1, 150)}{rng.choice(['', '', 'A', 'B', 'Bis'])} # {rng.randint(1, 120)}-{rng.randint(2, 99)}"
    if kind in ("APARTMENT", "STUDIO"):
        base += f" Apto {rng.randint(2, 18)}0{rng.randint(1, 4)}"
    elif kind == "OFFICE":
        base += f" Oficina {rng.randint(2, 12)}0{rng.randint(1, 9)}"
    elif kind == "COMMERCIAL":
        base += f" Local {rng.randint(1, 20)}"
    return base[:255]


TYPE_LABEL = {"APARTMENT": "Apartamento", "HOUSE": "Casa", "STUDIO": "Apartaestudio", "OFFICE": "Oficina",
              "COMMERCIAL": "Local comercial", "COUNTRY_HOUSE": "Casa campestre"}
NAME_EXTRAS = {"APARTMENT": ["con balcón", "con vista a la ciudad", "remodelado", "cerca al parque", "iluminado",
                             "con parqueadero", "de tres habitaciones", ""],
               "HOUSE": ["de dos pisos", "con patio", "esquinera", "con terraza", "familiar", "colonial", ""],
               "STUDIO": ["amoblado", "para estudiante", "cerca a universidades", ""],
               "OFFICE": ["con sala de juntas", "en edificio empresarial", ""],
               "COMMERCIAL": ["esquinero", "sobre avenida", "con vitrina", ""],
               "COUNTRY_HOUSE": ["con zonas verdes", "con chimenea", ""]}
FEATURES = ["piso en madera laminada", "cocina integral con mesón en granito", "zona de ropas independiente",
            "ventanales con mucha luz natural", "portería 24 horas", "gimnasio y salón comunal",
            "cerca a transporte público", "parque infantil en el conjunto", "baños remodelados",
            "calentador a gas", "chimenea en la sala", "estudio adicional", "terraza con vista a los cerros",
            "conjunto con piscina", "a dos cuadras de centros comerciales", "barrio tranquilo y arborizado",
            "parqueadero cubierto", "cuarto útil", "balcón con vista a la calle"]


def listing_price(city, kind, area, stratum, listing):
    per_m2 = CITIES[city][1] * STRATUM_FACTOR[stratum]
    if kind == "COMMERCIAL":
        per_m2 *= 1.4
    elif kind == "COUNTRY_HOUSE":
        per_m2 *= 0.55
    rent = max(650000, round(area * per_m2 / 50000) * 50000)
    if listing == "SALE":
        return round(rent * rng.uniform(160, 210) / 5000000) * 5000000
    return rent


# ---------------------------------------------------------------- medios
media_count = {"n": 0}


def media(owner, purpose, category, tag, created, choice=None):
    pool = images[(category, tag)]
    item = choice if choice is not None else rng.choice(pool)
    asset_id = uid("media", media_count["n"])
    media_count["n"] += 1
    stem = item["file"].rsplit(".", 1)[0]
    sql.add("media_assets", {"id": asset_id, "owner_user_id": owner["id"], "purpose": purpose,
                             "object_key": f"seed/{stem}/{asset_id}.jpg", "content_type": "image/jpeg",
                             "size_bytes": item["bytes"], "status": "ATTACHED",
                             "upload_expires_at": T(created + 900), "confirmed_at": T(created + 60),
                             "created_at": T(created)})
    return asset_id


# ---------------------------------------------------------------- técnicos
SPECIALTIES = ["PLUMBING", "ELECTRICAL", "PAINTING", "CARPENTRY", "MASONRY", "GENERAL"]
SPEC_LABEL = {"PLUMBING": "plomería", "ELECTRICAL": "electricidad", "PAINTING": "pintura",
              "CARPENTRY": "carpintería", "MASONRY": "obra blanca y mampostería", "GENERAL": "mantenimiento general"}
BIOS = ["Trabajo con garantía escrita y dejo el sitio limpio al terminar.",
        "Más de diez años atendiendo conjuntos residenciales y casas de familia.",
        "Atiendo urgencias el mismo día en mi zona. Llevo herramienta y repuestos básicos.",
        "Técnico certificado, con experiencia en remodelaciones completas.",
        "Me gusta explicarle al cliente qué falló y cómo evitar que vuelva a pasar.",
        "Hago visitas de diagnóstico sin costo si el trabajo se contrata conmigo."]

fixers = []
FIXER_PLAN = ([("Bogotá", ["PLUMBING", "ELECTRICAL", "GENERAL"])] +
              [(c, None) for c in ["Bogotá"] * 15 + ["Medellín"] * 8 + ["Cali"] * 5 + ["Barranquilla"] * 4 +
               ["Cartagena"] * 3 + ["Bucaramanga"] * 3 + ["Envigado"] * 2 + ["Pereira"] * 2 + ["Manizales"] * 2 +
               ["Santa Marta"] * 2 + ["Armenia", "Chía", "Villavicencio"]])
for index, (city, specs) in enumerate(FIXER_PLAN):
    if index == 0:
        user = users["fixer"]
    else:
        user = make_user(f"fixer-{index:02d}", person_name(), ["FIXER"], rng.randint(120, 650))
    specialties = specs or rng.sample(SPECIALTIES, rng.choice([1, 2, 2, 3]))
    status = "VERIFIED"
    if index >= 44:
        status = "PENDING"
    elif index in (41, 42, 43):
        status = rng.choice(["PENDING", "REJECTED"])
    user.update({"city": city, "specialties": specialties, "status": status, "ratings": []})
    fixers.append(user)
    neighborhood = rng.choice(CITIES[city][2])
    created = -rng.randint(90, 600) * DAY
    submitted = created + rng.randint(1, 10) * DAY
    decided = submitted + rng.randint(1, 4) * DAY
    row = {"user_id": user["id"], "verification_status": status, "created_at": T(created), "updated_at": T(decided),
           "submitted_at": None, "decided_at": None, "decided_by": None, "rejection_reason": None,
           "consent_accepted_at": None, "consent_version": None, "city": city,
           "latitude": round(neighborhood[1] + rng.uniform(-0.01, 0.01), 6),
           "longitude": round(neighborhood[2] + rng.uniform(-0.01, 0.01), 6),
           "bio": rng.choice(BIOS) if status == "VERIFIED" else None,
           "years_experience": rng.randint(2, 28), "rating_average": None, "rating_count": 0}
    if index >= 44:
        # Applicants who sent documents in the last days: the admin review queue.
        submitted = -rng.randint(1, 6) * DAY - rng.randint(0, 20) * HOUR
        row.update({"submitted_at": T(submitted), "consent_accepted_at": T(submitted), "consent_version": "2026-09"})
    elif status in ("VERIFIED", "REJECTED") or index in (41, 42, 43):
        if status == "PENDING":
            # Siguen en revisión: enviaron hace un par de semanas, no hace meses.
            submitted = -rng.randint(7, 16) * DAY - rng.randint(0, 20) * HOUR
        row.update({"submitted_at": T(submitted), "consent_accepted_at": T(submitted), "consent_version": "2026-09"})
        if status != "PENDING":
            row.update({"decided_at": T(decided), "decided_by": admins[index % 2]["id"]})
        if status == "REJECTED":
            row["rejection_reason"] = "El certificado de oficio no es legible. Súbelo de nuevo con mejor luz."
    user["profile_row"] = row
    user["created"] = created
    for specialty in specialties:
        sql.add("fixer_specialties", {"fixer_user_id": user["id"], "specialty": specialty})
    if row["submitted_at"] is not None:
        for doc_type, name in (("ID_CARD", "cedula-prueba"), ("TRADE_CERTIFICATE", "certificado-prueba")):
            media_id = media(user, "FIXER_VERIFICATION", "verificacion", name, submitted - HOUR)
            user.setdefault("docs", []).append({"user_id": user["id"], "document_type": doc_type,
                                                "submitted_at": row["submitted_at"], "media_id": media_id})

verified = [f for f in fixers if f["status"] == "VERIFIED"]


def fixers_for(specialty, city):
    local = [f for f in verified if specialty in f["specialties"] and f["city"] == city]
    if len(local) < 2:
        local += [f for f in verified if specialty in f["specialties"] and f not in local]
    return local


# ---------------------------------------------------------------- inmuebles
properties = []
PROTAGONIST_HOMES = [("Bogotá", "Chapinero Alto", "APARTMENT", "RENT", 4, "PUBLISHED"),
                     ("Bogotá", "Usaquén", "APARTMENT", "RENT", 5, "PUBLISHED"),
                     ("Bogotá", "Teusaquillo", "HOUSE", "SALE", 4, "PUBLISHED"),
                     ("Medellín", "Laureles", "HOUSE", "RENT", 4, "PUBLISHED"),
                     ("Medellín", "El Poblado", "STUDIO", "RENT", 5, "PUBLISHED"),
                     ("Bogotá", "Cedritos", "APARTMENT", "RENT", 4, "DRAFT"),
                     ("Bogotá", "Galerías", "APARTMENT", "RENT", 3, "ARCHIVED"),
                     ("Cartagena", "Manga", "HOUSE", "RENT", 4, "ARCHIVED")]


def make_property(owner, city, neighborhood_name=None, kind=None, listing=None, stratum=None, status=None,
                  manager=None):
    neighborhoods = CITIES[city][2]
    hood = next((n for n in neighborhoods if n[0] == neighborhood_name), None) or rng.choice(neighborhoods)
    kind = kind or rng.choices(["APARTMENT", "HOUSE", "STUDIO", "OFFICE", "COMMERCIAL", "COUNTRY_HOUSE"],
                               weights=[52, 24, 9, 6, 6, 3])[0]
    if kind == "COUNTRY_HOUSE" and city not in ("Chía", "Villavicencio", "Armenia", "Pereira", "Manizales"):
        kind = "HOUSE"
    listing = listing or ("SALE" if rng.random() < 0.22 else "RENT")
    stratum = stratum or rng.choices([2, 3, 4, 5, 6], weights=[8, 26, 32, 20, 14])[0]
    area = {"STUDIO": rng.randint(28, 45), "APARTMENT": rng.randint(52, 145), "HOUSE": rng.randint(90, 260),
            "OFFICE": rng.randint(40, 180), "COMMERCIAL": rng.randint(30, 200),
            "COUNTRY_HOUSE": rng.randint(180, 600)}[kind]
    bedrooms = {"STUDIO": 1, "APARTMENT": rng.choice([1, 2, 2, 3, 3, 3, 4]), "HOUSE": rng.choice([3, 3, 4, 4, 5]),
                "OFFICE": 0, "COMMERCIAL": 0, "COUNTRY_HOUSE": rng.choice([3, 4, 5, 6])}[kind]
    bathrooms = max(1, min(bedrooms, rng.choice([1, 2, 2, 3]))) if bedrooms else rng.choice([1, 1, 2])
    parking = 0 if kind == "STUDIO" and rng.random() < 0.6 else rng.choice([0, 1, 1, 1, 2])
    status = status or rng.choices(["PUBLISHED", "DRAFT", "ARCHIVED"], weights=[85, 8, 7])[0]
    created = -rng.randint(20, 520) * DAY
    extra = rng.choice(NAME_EXTRAS[kind])
    name = f"{TYPE_LABEL[kind]} {extra + ' ' if extra else ''}en {hood[0]}".replace("  ", " ")
    features = rng.sample(FEATURES, 3)
    description = (f"{TYPE_LABEL[kind]} de {area} m² en {hood[0]}, {city}. "
                   + ("Tiene " + str(bedrooms) + (" habitación" if bedrooms == 1 else " habitaciones") + " y "
                      + str(bathrooms) + (" baño" if bathrooms == 1 else " baños") + ". " if bedrooms else "")
                   + f"Destacamos: {features[0]}, {features[1]} y {features[2]}.")
    property_id = uid("property", len(properties))
    price = listing_price(city, kind, area, stratum, listing)
    admin_fee = None
    if kind in ("APARTMENT", "STUDIO", "OFFICE"):
        admin_fee = round(area * {2: 2200, 3: 2800, 4: 3800, 5: 5200, 6: 7000}[stratum] / 10000) * 10000
    record = {"id": property_id, "owner": owner, "city": city, "neighborhood": hood[0], "kind": kind,
              "listing": listing, "price": price, "status": status, "name": name, "manager": manager,
              "address": address(city, kind), "created": created, "photos": []}
    published_at = T(created + rng.randint(0, 5) * DAY) if status != "DRAFT" else None
    sql.add("properties", {
        "id": property_id, "owner_user_id": owner["id"], "name": name, "address": record["address"], "city": city,
        "area_m2": area, "created_at": T(created), "updated_at": T(created + DAY), "status": status,
        "published_at": published_at, "published_by_user_id": owner["id"] if status != "DRAFT" else None,
        "latitude": round(hood[1] + rng.uniform(-0.006, 0.006), 6),
        "longitude": round(hood[2] + rng.uniform(-0.006, 0.006), 6),
        "listing_type": listing, "property_type": kind, "price": price, "admin_fee": admin_fee,
        "bedrooms": bedrooms, "bathrooms": bathrooms, "parking_spots": parking, "stratum": stratum,
        "neighborhood": hood[0], "description": description, "manager_user_id": manager["id"] if manager else None})
    # Gallery: facade of the right kind first, then rooms.
    cover_tag = {"APARTMENT": "apartamento", "STUDIO": "apartamento", "HOUSE": "casa", "COUNTRY_HOUSE": "casa",
                 "OFFICE": "oficina", "COMMERCIAL": "casa"}[kind]
    rooms = {"APARTMENT": ["sala", "cocina", "habitacion", "bano", "comedor"],
             "STUDIO": ["sala", "cocina", "bano"], "HOUSE": ["sala", "cocina", "habitacion", "comedor", "bano"],
             "COUNTRY_HOUSE": ["sala", "cocina", "habitacion"], "OFFICE": ["sala"], "COMMERCIAL": ["sala"]}[kind]
    count = 0 if status == "DRAFT" and owner is not users["owner"] else (
        4 if owner["protagonist"] else rng.choice([1, 2, 2, 3]))
    tags = [cover_tag] + rng.sample(rooms, min(len(rooms), max(0, count - 1)))
    for order, tag in enumerate(tags[:count]):
        media_id = media(owner, "PROPERTY_PHOTO", "propiedad", tag, created + HOUR)
        sql.add("property_photos", {"property_id": property_id, "photo_order": order, "media_id": media_id})
    properties.append(record)
    return record


for spec in PROTAGONIST_HOMES:
    city, hood, kind, listing, stratum, status = spec
    make_property(users["owner"], city, hood, kind, listing, stratum, status)
for owner in owners[1:]:
    for _ in range(rng.choice([2, 3, 3, 4, 5, 6, 8])):
        manager = None
        if rng.random() < 0.18:
            manager = users["manager"] if rng.random() < 0.55 else rng.choice(managers[1:])
        make_property(owner, pick_city(), manager=manager)
# Properties the managers own directly (inmobiliarias with their own stock).
for manager in managers:
    for _ in range(rng.randint(3, 6)):
        make_property(manager, pick_city())
# Delegate two of the protagonist owner's homes... no: the protagonist manager also manages one of them.
sql.tables["properties"][3]["manager_user_id"] = users["manager"]["id"]
properties[3]["manager"] = users["manager"]

# ---------------------------------------------------------------- contratos
contracts = []
tenant_home = {}
rentable = [p for p in properties if p["listing"] == "RENT" and p["kind"] in ("APARTMENT", "HOUSE", "STUDIO")]
rng.shuffle(rentable)
protagonist_home = properties[0]  # Chapinero Alto, owned by the protagonist owner
rentable.remove(protagonist_home)
if properties[1] in rentable:
    rentable.remove(properties[1])
rentable.insert(0, protagonist_home)


def add_contract(prop, tenant, start_days, months, status, renewals=0):
    contract_id = uid("contract", len(contracts))
    start = start_days
    end = start_days + months * 30
    rent = prop["price"]
    reminders = status in ("EXPIRED", "TERMINATED")
    sql.add("rental_contracts", {
        "id": contract_id, "property_id": prop["id"], "owner_user_id": prop["owner"]["id"],
        "tenant_user_id": tenant["id"], "start_date": D(start), "end_date": D(end), "monthly_rent_amount": rent,
        "deposit_amount": rent, "payment_day_of_month": rng.choice([1, 5, 5, 10, 15]), "status": status,
        "renewal_notice_days": 30, "notes": rng.choice([
            "Incluye un parqueadero y depósito.", "Arriendo con incremento anual según el IPC.",
            "El arrendatario asume servicios públicos y administración.", "Contrato a un año con opción de renovación.",
            None]),
        "reminder_30d_sent": reminders or end < 30, "reminder_7d_sent": reminders or end < 7,
        "reminder_expired_sent": reminders, "created_at": T((start - 5) * DAY), "updated_at": T((start - 5) * DAY)})
    contracts.append({"id": contract_id, "property": prop, "tenant": tenant, "status": status, "end": end})
    return contract_id


add_contract(protagonist_home, users["tenant"], -150, 12, "ACTIVE")
tenant_home[users["tenant"]["id"]] = protagonist_home
tenant_pool = tenants[1:]
for index, prop in enumerate(rentable[1:70]):
    tenant = tenant_pool[index % len(tenant_pool)]
    if index < 48:
        start = -rng.randint(20, 330)
        add_contract(prop, tenant, start, 12, "ACTIVE")
        tenant_home.setdefault(tenant["id"], prop)
    elif index < 60:
        add_contract(prop, tenant, -rng.randint(400, 700), 12, "EXPIRED")
    elif index < 64:
        add_contract(prop, tenant, -rng.randint(380, 500), 12, "TERMINATED")
    else:
        add_contract(prop, tenant, -rng.randint(370, 420), 12, "RENEWED")
# One contract of the protagonist owner about to expire, to show the reminder.
add_contract(properties[1], tenants[5], -340, 12, "ACTIVE")

# ---------------------------------------------------------------- solicitudes, cotizaciones, trabajos
CATALOG = {
    "PLUMBING": [("Fuga en el sifón del lavaplatos", "El sifón del lavaplatos gotea desde hace una semana y el mueble ya se hinchó con la humedad."),
                 ("Sanitario que no deja de correr agua", "El tanque del sanitario del baño principal sigue llenándose todo el tiempo."),
                 ("Baja presión en la ducha", "La ducha del segundo piso casi no tiene presión desde que cambiaron el registro."),
                 ("Desagüe del lavadero tapado", "El agua del lavadero se devuelve y huele mal cuando llueve."),
                 ("Mancha de humedad bajo el baño", "Apareció una mancha en el techo de la cocina justo debajo del baño del segundo piso."),
                 ("Cambio de grifería de cocina", "La llave de la cocina gotea por la base; quiero cambiarla por una monomando."),
                 ("Tubería rota en el patio", "Se rompió un tubo en el patio y hay un charco permanente."),
                 ("Tanque de reserva con fuga", "El tanque de reserva de la terraza pierde agua por la flotadora.")],
    "ELECTRICAL": [("Se dispara el breaker de la cocina", "Cada vez que prendo el microondas y la nevera juntos se baja el breaker."),
                   ("Tomas sin corriente en la habitación", "Dos tomas de la habitación principal dejaron de funcionar."),
                   ("Instalar lámparas LED en la sala", "Necesito instalar seis lámparas panel LED y cambiar los interruptores."),
                   ("Chispa en el toma del microondas", "El toma de la cocina saca chispa al conectar el microondas."),
                   ("Revisión del tablero eléctrico", "Quiero una revisión del tablero antes de entregar el apartamento al nuevo arrendatario."),
                   ("Circuito para estufa eléctrica", "Compré una estufa de inducción y necesito un circuito independiente.")],
    "PAINTING": [("Pintar apartamento antes de entregar", "Pintura completa de muros y techos, color blanco hueso, apartamento de tres habitaciones."),
                 ("Pared con salitre en la sala", "La pared de la sala que da al patio tiene salitre y la pintura se está levantando."),
                 ("Retocar fachada descascarada", "La fachada tiene partes descascaradas por el sol y la lluvia."),
                 ("Techo con manchas de humedad", "Hay que tratar y pintar el techo del baño que quedó manchado."),
                 ("Esmaltar rejas y puertas metálicas", "Las rejas de las ventanas tienen óxido; hay que lijar y esmaltar.")],
    "CARPENTRY": [("Puerta del clóset descolgada", "Una de las puertas corredizas del clóset se salió del riel."),
                  ("Gabinetes de cocina hinchados", "Los gabinetes bajo el lavaplatos se hincharon por una fuga antigua."),
                  ("Piso laminado levantado", "El piso laminado de la sala se levantó cerca del balcón."),
                  ("Cambiar chapas de las puertas", "Quiero cambiar las chapas de las cuatro puertas interiores."),
                  ("Instalar clóset en habitación auxiliar", "Necesito un clóset de 2 metros en melamina con cajones.")],
    "MASONRY": [("Grieta en la pared del patio", "Apareció una grieta diagonal en el muro del patio después del temblor."),
                ("Cambiar baldosas rotas del baño", "Hay cinco baldosas rotas en el piso del baño social."),
                ("Impermeabilizar terraza", "Cuando llueve se filtra agua de la terraza al apartamento de abajo."),
                ("Enchapar salpicadero de la cocina", "Quiero enchapar el salpicadero de la cocina con cerámica blanca.")],
    "GENERAL": [("Mantenimiento general antes de arrendar", "Revisión general: chapas, grifos, bombillos, silicona de baños y pequeños resanes."),
                ("Instalar cortinas y soporte de TV", "Instalar tres rieles de cortina y un soporte de TV de 55 pulgadas."),
                ("Arreglar canal de aguas lluvias", "La canal del techo está suelta y el agua cae sobre la puerta de entrada."),
                ("Instalar repisas y espejos", "Instalar dos repisas flotantes y un espejo grande en el baño.")],
}
AMOUNTS = {"PLUMBING": (120000, 900000), "ELECTRICAL": (150000, 1200000), "PAINTING": (450000, 4200000),
           "CARPENTRY": (250000, 3200000), "MASONRY": (400000, 6000000), "GENERAL": (90000, 650000)}
QUOTE_MESSAGES = ["Incluye materiales y garantía de tres meses.", "Puedo ir mañana en la mañana a revisar.",
                  "El valor incluye retiro de escombros.", "Termino en dos días, con materiales de primera.",
                  "Precio con IVA incluido. Llevo el repuesto original.", "Hago primero una visita de diagnóstico sin costo.",
                  "Trabajo limpio, protejo pisos y muebles.", None]
CHAT = [("cliente", "Buenos días, ¿a qué hora puede venir?"),
        ("tecnico", "Buenos días. Puedo llegar a las 9:00 a. m., ¿le sirve?"),
        ("cliente", "Sí, perfecto. En portería avise que va para el apartamento."),
        ("tecnico", "Listo. ¿Hay parqueadero de visitantes?"),
        ("cliente", "Sí, en el sótano 2."),
        ("tecnico", "Ya revisé: el problema era el empaque. Mañana traigo el repuesto."),
        ("cliente", "¿El valor sigue siendo el mismo de la cotización?"),
        ("tecnico", "Sí señora, todo incluido."),
        ("tecnico", "Le envío fotos del avance."),
        ("cliente", "Quedó muy bien, muchas gracias."),
        ("tecnico", "Con gusto. Cualquier cosa me escribe por aquí.")]
REVIEWS = {5: ["Muy puntual y dejó todo limpio.", "Excelente trabajo, lo recomiendo.",
               "Explicó qué estaba fallando y lo dejó funcionando el mismo día.", "Rápido, amable y cumplió lo cotizado.",
               "La pintura quedó pareja, muy profesional.", None],
           4: ["Buen trabajo, aunque llegó una hora tarde.", "Cumplió con lo cotizado, sin cobros extra.",
               "Tuvo que volver al día siguiente por un repuesto, pero quedó bien.", None],
           3: ["Se demoró más de lo acordado.", "El trabajo quedó bien pero dejó escombros."],
           2: ["Tuvo que volver dos veces por el mismo problema."]}

requests_out = []
counters = {"request": 0, "quotation": 0, "job": 0, "message": 0, "rating": 0, "visit": 0, "notification": 0,
            "payout": 0, "remodeling": 0}
notifications = []


def notify(recipient, kind, title, body, navigate, entity_id, entity_type, when, read=None):
    if read is None:
        read = when < -3 * DAY
    counters["notification"] += 1
    notifications.append({"id": uid("notification", counters["notification"]), "recipient_user_id": recipient["id"],
                          "type": kind, "title": title, "body": body, "navigate_to": navigate,
                          "entity_id": entity_id, "entity_type": entity_type, "data": None,
                          "read_at": T(when + HOUR) if read else None, "created_at": T(when)})


def tenant_of(prop):
    for contract in contracts:
        if contract["property"] is prop and contract["status"] == "ACTIVE":
            return contract["tenant"]
    return None


def business_time(base):
    """Lleva un instante relativo al día más cercano y a una hora hábil de Colombia (7:00 a 16:00)."""
    return BizTime(round(base / DAY), rng.choice([7, 8, 9, 10, 11, 14, 15, 16]) * 60)


def create_request(prop, status, created, specialty=None, reporter=None, urgency=None, title_desc=None,
                   fixer=None, rated=None, project=None):
    specialty = specialty or rng.choices(SPECIALTIES, weights=[30, 20, 15, 12, 10, 13])[0]
    title, description = title_desc or rng.choice(CATALOG[specialty])
    counters["request"] += 1
    request_id = uid("request", counters["request"])
    owner = prop["owner"]
    reporter = reporter or owner
    urgency = urgency or rng.choices(["LOW", "MEDIUM", "HIGH", "URGENT"], weights=[18, 50, 22, 10])[0]
    record = {"id": request_id, "property": prop, "owner": owner, "reporter": reporter, "specialty": specialty,
              "title": title, "status": status, "created": created, "manager": prop["manager"]}
    candidates = [f for f in fixers_for(specialty, prop["city"]) if f["id"] != owner["id"]]
    # Una solicitud abierta de menos de 12 horas aún no tiene ofertas; las de las cuentas protagonistas
    # tienen varias para poder comparar en la demostración.
    if status != "OPEN":
        count = rng.choice([1, 2, 2, 3, 3, 4])
    elif created > -12 * HOUR:
        count = 0
    elif owner.get("protagonist") or (prop["manager"] and prop["manager"].get("protagonist")):
        count = rng.choice([2, 3, 3, 4])
    else:
        count = rng.choice([0, 1, 2, 2, 3])
    quoted = rng.sample(candidates, min(count, len(candidates)))
    if fixer is not None:
        if fixer in quoted:
            quoted.remove(fixer)
        quoted.insert(0, fixer)
    assigned = quoted[0] if status in ("ASSIGNED", "COMPLETED", "SLA_WARNING", "SLA_BREACHED") and quoted else None
    if status in ("ASSIGNED", "COMPLETED") and assigned is None:
        status = "OPEN"
        record["status"] = status
    completed_at = None
    if status == "COMPLETED":
        completed_at = min(-HOUR * 3, created + rng.randint(3, 20) * DAY)
    sla_deadline = created + 48 * HOUR if urgency == "URGENT" else None
    sql.add("repair_requests", {
        "id": request_id, "owner_user_id": owner["id"], "specialty": specialty, "title": title,
        "description": description, "status": status, "assigned_fixer_user_id": assigned["id"] if assigned else None,
        "created_at": T(created), "updated_at": T(completed_at or created + HOUR), "property_id": prop["id"],
        "urgency_level": urgency, "sla_deadline": T(sla_deadline) if sla_deadline else None,
        "property_city": prop["city"],
        "last_escalation_notified_at": T(sla_deadline) if status in ("SLA_WARNING", "SLA_BREACHED") else None,
        "requested_by_user_id": reporter["id"], "manager_user_id": prop["manager"]["id"] if prop["manager"] else None,
        "property_name": prop["name"], "property_address": prop["address"],
        "completed_at": T(completed_at) if completed_at else None})
    photo_tag = specialty if ("solicitud", specialty) in images else None
    for order in range(rng.choice([0, 1, 1, 2]) if photo_tag else 0):
        media_id = media(reporter, "REPAIR_REQUEST", "solicitud", photo_tag, created - 600)
        sql.add("repair_request_photos", {"request_id": request_id, "photo_order": order, "media_id": media_id})
    if reporter is not owner:
        notify(owner, "REQUEST_REPORTED", "Nueva falla reportada en tu inmueble",
               f"\"{title}\" en {prop['name']}.", f"/requests/{request_id}", request_id, "REQUEST", created + 60)
    low, high = AMOUNTS[specialty]
    accepted_quote = None
    for position, quoting in enumerate(quoted):
        counters["quotation"] += 1
        quotation_id = uid("quotation", counters["quotation"])
        amount = round(rng.uniform(low, high) / 10000) * 10000
        if position == 0 and assigned:
            q_status = "ACCEPTED"
        elif status in ("OPEN", "SLA_WARNING", "SLA_BREACHED") and not assigned:
            q_status = "SUBMITTED"
        elif status == "CANCELLED":
            q_status = "SUBMITTED" if rng.random() < 0.5 else "REJECTED"
        else:
            q_status = "REJECTED"
        quote_time = min(created + rng.randint(1, 30) * HOUR, -2 * HOUR - position * 600)
        sql.add("quotations", {"id": quotation_id, "request_id": request_id, "fixer_user_id": quoting["id"],
                               "amount": amount, "estimated_days": rng.randint(1, 10),
                               "message": rng.choice(QUOTE_MESSAGES), "status": q_status,
                               "created_at": T(quote_time), "updated_at": T(quote_time + HOUR)})
        notify(owner, "QUOTATION_RECEIVED", "Nueva cotización recibida",
               f"Llegó una oferta por ${amount:,}".replace(",", ".") + f" para \"{title}\".",
               f"/quotations/request/{request_id}", quotation_id, "QUOTATION", quote_time)
        if q_status == "ACCEPTED":
            accepted_quote = (quotation_id, amount, quote_time)
    record.update({"assigned": assigned, "quoted": quoted})
    if accepted_quote:
        create_job(record, accepted_quote, completed_at, rated)
    if project is not None:
        record["project"] = project
    requests_out.append(record)
    return record


def create_job(request, accepted, completed_at, rated):
    quotation_id, amount, accepted_at = accepted
    accept_time = min(accepted_at + rng.randint(2, 30) * HOUR, -HOUR)
    counters["job"] += 1
    job_id = uid("job", counters["job"])
    fixer = request["assigned"]
    owner = request["owner"]
    payer = request["manager"] if request["manager"] and rng.random() < 0.5 else owner
    if request["status"] == "COMPLETED":
        job_status = "COMPLETED"
    else:
        job_status = rng.choice(["ASSIGNED", "SCHEDULED", "SCHEDULED", "IN_PROGRESS"])
    scheduled_for = None
    started_at = None
    log = [("ASSIGNED", f"Cotización aceptada por ${amount:,}".replace(",", "."), owner, accept_time)]
    if job_status == "IN_PROGRESS":
        started_at = max(accept_time + 600, -rng.randint(2, 30) * HOUR)
    elif job_status == "COMPLETED":
        started_at = max(accept_time + 600, completed_at - rng.randint(4, 30) * HOUR)
    if job_status == "SCHEDULED":
        scheduled_for = business_time(rng.randint(1, 9) * DAY)
    elif started_at is not None:
        scheduled_for = started_at
    if scheduled_for is not None:
        log.append(("SCHEDULED", rng.choice(["Llevo los materiales.", "Confirmado con el cliente.", None]), fixer,
                    min(accept_time + 300, (started_at or 0) - 300, -2 * HOUR)))
    if started_at is not None:
        log.append(("IN_PROGRESS", rng.choice(["Empecé con el desmonte.", "Cerré el registro general.", None]),
                    fixer, started_at))
    if job_status == "COMPLETED":
        log.append(("COMPLETED", rng.choice(["Trabajo terminado y probado.", "Quedó funcionando, sin fugas.", None]),
                    fixer, completed_at))
    created_job = accept_time
    sql.add("jobs", {"id": job_id, "request_id": request["id"], "quotation_id": quotation_id,
                     "fixer_user_id": fixer["id"], "owner_user_id": owner["id"], "status": job_status,
                     "created_at": T(created_job),
                     "completed_at": T(completed_at) if job_status == "COMPLETED" else None,
                     "updated_at": T(log[-1][3]), "scheduled_for": T(scheduled_for) if scheduled_for else None,
                     "started_at": T(started_at) if started_at else None, "progress_note": log[-1][1]})
    for status, note, actor, when in log:
        sql.add("job_status_log", {"id": uid("joblog", job_id, status), "job_id": job_id, "status": status,
                                   "note": note, "changed_by_user_id": actor["id"], "changed_at": T(when)})
    notify(fixer, "QUOTATION_ACCEPTED", "Aceptaron tu cotización",
           f"Tu oferta para \"{request['title']}\" fue aceptada.", "/jobs/me", quotation_id, "QUOTATION", accept_time)
    # Payment: completed jobs are paid; open jobs are paid about half the time.
    paid = job_status == "COMPLETED" or rng.random() < 0.55
    charge_id = uid("charge", quotation_id)
    paid_at = accept_time + rng.randint(1, 48) * HOUR
    if job_status == "COMPLETED":
        paid_at = min(paid_at, completed_at - HOUR)
    paid_at = min(paid_at, -HOUR)
    reference = "FXR-" + "".join(rng.choice("ABCDEFGHJKLMNPQRSTUVWXYZ23456789") for _ in range(8))
    sql.add("owner_payments", {
        "id": charge_id, "quotation_id": quotation_id, "request_id": request["id"],
        "property_id": request["property"]["id"], "owner_user_id": owner["id"],
        "manager_user_id": request["manager"]["id"] if request["manager"] else None, "fixer_user_id": fixer["id"],
        "specialty": request["specialty"], "concept": request["title"], "amount": amount, "reference": reference,
        "status": "APPROVED" if paid else "PENDING",
        "method": rng.choice(["SANDBOX_CARD", "SANDBOX_PSE", "SANDBOX_NEQUI"]) if paid else None,
        "gateway_transaction_id": ("SBX-" + uid("tx", charge_id)[:13].upper()) if paid else None,
        "failure_reason": None, "attempts": 1 if paid else 0, "paid_by_user_id": payer["id"] if paid else None,
        "created_at": T(accept_time), "updated_at": T(paid_at if paid else accept_time),
        "approved_at": T(paid_at) if paid else None})
    if paid:
        notify(fixer, "PAYMENT_APPROVED", "El cliente pagó el trabajo",
               f"Recibimos el pago de \"{request['title']}\". Queda en garantía hasta que cierres el trabajo.",
               "/payments/earnings", charge_id, "CHARGE", paid_at)
    commission = amount * 1000 // 10000
    earning_status = "HELD"
    released_at = None
    if job_status == "COMPLETED" and paid:
        earning_status = "AVAILABLE"
        released_at = max(completed_at, paid_at)
    sql.add("fixer_earnings", {
        "id": uid("earning", quotation_id), "quotation_id": quotation_id, "fixer_user_id": fixer["id"],
        "gross_amount": amount, "commission_amount": commission, "net_amount": amount - commission,
        "commission_rate_bps": 1000, "status": earning_status, "created_at": T(accept_time),
        "released_at": T(released_at) if released_at else None, "paid_out_at": None,
        "updated_at": T(released_at or accept_time), "funded_at": T(paid_at) if paid else None,
        "job_completed_at": T(completed_at) if job_status == "COMPLETED" else None})
    fixer.setdefault("earnings", []).append({"id": uid("earning", quotation_id), "net": amount - commission,
                                             "status": earning_status, "released": released_at})
    request.update({"job_id": job_id, "job_status": job_status, "amount": amount, "accept_time": accept_time,
                    "completed_at": completed_at, "scheduled_for": scheduled_for})
    # Conversation while the job is active or after it.
    participants = [request["reporter"] if request["reporter"] is not fixer else owner]
    for index in range(rng.randint(2, 8) if request["status"] != "CANCELLED" else 0):
        side, body = CHAT[min(index, len(CHAT) - 1)] if index < 4 else rng.choice(CHAT[4:])
        sender = fixer if side == "tecnico" else participants[0]
        when = accept_time + (index + 1) * rng.randint(1, 6) * HOUR
        if completed_at is not None:
            when = min(when, completed_at - 60 * (8 - index))
        when = min(when, -60 * (9 - index))
        counters["message"] += 1
        sql.add("chat_messages", {"id": uid("message", counters["message"]), "request_id": request["id"],
                                  "sender_user_id": sender["id"], "body": body, "sent_at": T(when),
                                  "notification_status": "SKIPPED"})
    if job_status == "COMPLETED":
        notify(request["reporter"], "JOB_COMPLETED", "Trabajo terminado",
               f"El técnico cerró \"{request['title']}\". Revisa las evidencias y califica el servicio.",
               f"/requests/{request['id']}", job_id, "JOB", completed_at)
        work_rating = None
        if rated if rated is not None else rng.random() < 0.78:
            work_rating = rng.choices([5, 4, 3, 2], weights=[62, 28, 8, 2])[0]
            counters["rating"] += 1
            rater = request["reporter"] if request["reporter"] in tenants else owner
            sql.add("service_ratings", {
                "id": uid("rating", job_id), "job_id": job_id, "request_id": request["id"],
                "fixer_user_id": fixer["id"], "rater_user_id": rater["id"], "score": work_rating,
                "punctuality": max(1, min(5, work_rating + rng.choice([-1, 0, 0, 1]))),
                "quality": work_rating, "cleanliness": max(1, min(5, work_rating + rng.choice([-1, 0, 0]))),
                "comment": rng.choice(REVIEWS[work_rating]), "created_at": T(completed_at + rng.randint(2, 40) * HOUR)})
            fixer["ratings"].append(work_rating)
            notify(fixer, "RATING_RECEIVED", f"Recibiste una calificación de {work_rating} de 5",
                   f"Un cliente calificó tu trabajo en \"{request['title']}\".", f"/fixers/{fixer['id']}/profile",
                   uid("rating", job_id), "RATING", completed_at + 3 * HOUR)
        sql.add("property_work_history", {
            "id": uid("work", job_id), "property_id": request["property"]["id"], "job_id": job_id,
            "request_id": request["id"], "specialty": request["specialty"], "title": request["title"],
            "fixer_user_id": fixer["id"], "completed_at": T(completed_at), "rating": work_rating})
        if rng.random() < 0.45 and ("obra", request["specialty"]) in images:
            for stage in rng.sample(["BEFORE", "AFTER"], rng.choice([1, 2])):
                media_id = media(fixer, "JOB_EVIDENCE", "obra" if stage == "AFTER" else "solicitud",
                                 request["specialty"] if stage == "AFTER" or ("solicitud", request["specialty"]) in images
                                 else "PLUMBING", completed_at - HOUR)
                sql.add("job_evidences", {"id": uid("evidence", media_id), "job_id": job_id, "media_id": media_id,
                                          "stage": stage, "caption": rng.choice(["Antes de intervenir", "Trabajo terminado",
                                                                                 "Detalle del arreglo", None]),
                                          "created_at": T(completed_at - HOUR)})


def property_owned(owner, status="PUBLISHED"):
    return [p for p in properties if p["owner"] is owner and (status is None or p["status"] == status)]


# Protagonist owner: a rich history across every state.
home_a, home_b, home_c, home_d, home_e = properties[0], properties[1], properties[2], properties[3], properties[4]
create_request(home_a, "COMPLETED", -95 * DAY, "PLUMBING", users["tenant"], "HIGH", CATALOG["PLUMBING"][0],
               fixer=users["fixer"], rated=True)
create_request(home_a, "ASSIGNED", -3 * DAY, "ELECTRICAL", users["tenant"], "MEDIUM", CATALOG["ELECTRICAL"][0],
               fixer=users["fixer"])
create_request(home_a, "OPEN", -20 * HOUR, "PAINTING", users["tenant"], "LOW", CATALOG["PAINTING"][1])
create_request(home_b, "OPEN", -2 * DAY, "PLUMBING", users["owner"], "MEDIUM", CATALOG["PLUMBING"][5])
create_request(home_b, "COMPLETED", -60 * DAY, "CARPENTRY", users["owner"], "LOW", CATALOG["CARPENTRY"][0], rated=False)
create_request(home_c, "COMPLETED", -200 * DAY, "MASONRY", users["owner"], "MEDIUM", CATALOG["MASONRY"][2], rated=True)
create_request(home_c, "ASSIGNED", -6 * DAY, "GENERAL", users["owner"], "LOW", CATALOG["GENERAL"][1],
               fixer=users["fixer"])
create_request(home_d, "OPEN", -30 * HOUR, "ELECTRICAL", users["owner"], "URGENT", CATALOG["ELECTRICAL"][3])
create_request(home_e, "COMPLETED", -130 * DAY, "PAINTING", users["owner"], "MEDIUM", CATALOG["PAINTING"][0], rated=True)
create_request(home_e, "CANCELLED", -40 * DAY, "GENERAL", users["owner"], "LOW", CATALOG["GENERAL"][3])

# Everyone else: about 300 requests spread over eighteen months.
weights = {"COMPLETED": 52, "ASSIGNED": 14, "OPEN": 24, "CANCELLED": 6, "SLA_WARNING": 2, "SLA_BREACHED": 2}
published = [p for p in properties if p["status"] != "DRAFT"]
for _ in range(380):
    prop = rng.choice(published)
    status = rng.choices(list(weights), weights=list(weights.values()))[0]
    if status == "COMPLETED":
        created = -rng.randint(10, 540) * DAY - rng.randint(0, 20) * HOUR
    elif status == "ASSIGNED":
        created = -rng.randint(2, 25) * DAY
    elif status in ("SLA_WARNING", "SLA_BREACHED"):
        created = -rng.randint(40, 70) * HOUR
    elif status == "CANCELLED":
        created = -rng.randint(10, 200) * DAY
    else:
        created = -rng.randint(1, 18) * DAY - rng.randint(0, 20) * HOUR
    reporter = prop["owner"]
    tenant = tenant_of(prop)
    if tenant is not None and rng.random() < 0.45:
        reporter = tenant
    elif prop["manager"] is not None and rng.random() < 0.5:
        reporter = prop["manager"]
    urgency = "URGENT" if status in ("SLA_WARNING", "SLA_BREACHED") else None
    fixer = None
    if status in ("COMPLETED", "ASSIGNED") and prop["city"] == "Bogotá" and rng.random() < 0.35:
        specialty = rng.choice(users["fixer"]["specialties"])
        create_request(prop, status, created, specialty, reporter, urgency, fixer=users["fixer"])
        continue
    if status == "OPEN" and prop["city"] == "Bogotá" and rng.random() < 0.55:
        create_request(prop, status, created, rng.choice(users["fixer"]["specialties"]), reporter, urgency)
        continue
    create_request(prop, status, created, None, reporter, urgency, fixer=fixer)

# Manager invitations on open managed requests.
for request in [r for r in requests_out if r["status"] == "OPEN" and r["manager"] is not None][:40]:
    candidates = [f for f in fixers_for(request["specialty"], request["property"]["city"])
                  if f["id"] != request["owner"]["id"] and f not in request["quoted"]]
    for fixer in rng.sample(candidates, min(2, len(candidates))):
        when = request["created"] + rng.randint(1, 10) * HOUR
        sql.add("request_invitations", {"id": uid("invitation", request["id"], fixer["id"]),
                                        "request_id": request["id"], "fixer_user_id": fixer["id"],
                                        "invited_by_user_id": request["manager"]["id"],
                                        "note": rng.choice(["Cliente prioritario.", "Necesitamos visita esta semana.", None]),
                                        "created_at": T(when)})
        notify(fixer, "REQUEST_INVITATION", "Te invitaron a cotizar",
               f"\"{request['title']}\" en {request['property']['city']}.", f"/requests/{request['id']}",
               request["id"], "REQUEST", when)

# ---------------------------------------------------------------- remodelaciones
REMODELINGS = [("Dejar el apartamento listo para arrendar", "PRE_RENTAL",
                [("PLUMBING", CATALOG["PLUMBING"][5]), ("PAINTING", CATALOG["PAINTING"][0]),
                 ("ELECTRICAL", CATALOG["ELECTRICAL"][4])]),
               ("Remodelación de cocina", "IMPROVEMENT",
                [("CARPENTRY", CATALOG["CARPENTRY"][1]), ("MASONRY", CATALOG["MASONRY"][3]),
                 ("ELECTRICAL", CATALOG["ELECTRICAL"][5])]),
               ("Arreglos de baño y terraza", "IMPROVEMENT",
                [("MASONRY", CATALOG["MASONRY"][1]), ("MASONRY", CATALOG["MASONRY"][2])]),
               ("Renovación antes de nuevo arrendatario", "PRE_RENTAL",
                [("PAINTING", CATALOG["PAINTING"][0]), ("GENERAL", CATALOG["GENERAL"][0])])]


def create_remodeling(owner, prop, title, goal, needs, state, created):
    counters["remodeling"] += 1
    project_id = uid("remodeling", counters["remodeling"])
    monthly = prop["price"] / 180 if prop["listing"] == "SALE" else prop["price"]
    target_rent = max(700000, round(monthly * 1.12 / 50000) * 50000) if goal == "PRE_RENTAL" else None
    request_ids = []
    for position, (specialty, title_desc) in enumerate(needs):
        status = {"done": "COMPLETED", "progress": rng.choice(["COMPLETED", "ASSIGNED"]) if position else "COMPLETED",
                  "new": "OPEN"}[state]
        record = create_request(prop, status, created + position * HOUR, specialty, owner, "MEDIUM", title_desc,
                                rated=state == "done")
        request_ids.append(record["id"])
    sql.add("remodeling_projects", {"id": project_id, "property_id": prop["id"], "owner_user_id": owner["id"],
                                    "title": title, "description": "Proyecto con varias especialidades coordinado desde Fix&Rent.",
                                    "created_at": T(created), "updated_at": T(created + DAY), "goal": goal,
                                    "target_rent": target_rent,
                                    "target_ready_date": D(int(created / DAY) + 45) if goal == "PRE_RENTAL" else None,
                                    "listing_published_at": None})
    for position, request_id in enumerate(request_ids):
        sql.add("remodeling_requirements", {"id": uid("requirement", project_id, position), "project_id": project_id,
                                            "request_id": request_id, "position": position})


# Protagonist: a pre-rental remodeling finished and waiting to publish, one in progress, one just created.
create_remodeling(users["owner"], properties[6], *REMODELINGS[0], "done", -35 * DAY)
create_remodeling(users["owner"], properties[2], *REMODELINGS[1], "progress", -12 * DAY)
create_remodeling(users["owner"], properties[5], *REMODELINGS[3], "new", -1 * DAY)
for index in range(14):
    owner = rng.choice(owners[1:])
    homes = property_owned(owner, None)
    if not homes:
        continue
    title, goal, needs = rng.choice(REMODELINGS)
    create_remodeling(owner, rng.choice(homes), title, goal, needs, rng.choice(["done", "progress", "new"]),
                      -rng.randint(5, 200) * DAY)

# ---------------------------------------------------------------- visitas técnicas
KITCHEN = ["Red de gas y gasodomésticos", "Tubería de agua, desagües y lavaplatos",
           "Tomas, iluminación y circuito eléctrico", "Gabinetes, puertas y herrajes", "Mesón y salpicadero",
           "Extracción y ventilación", "Pisos y enchapes"]


def add_visit(request, fixer, proposer, kind, status, when, minutes=60, outcome=None):
    counters["visit"] += 1
    visit_id = uid("visit", counters["visit"])
    prop = request["property"]
    sql.add("technical_visits", {
        "id": visit_id, "request_id": request["id"], "property_id": prop["id"], "owner_user_id": request["owner"]["id"],
        "requested_by_user_id": request["reporter"]["id"],
        "manager_user_id": request["manager"]["id"] if request["manager"] else None, "fixer_user_id": fixer["id"],
        "proposed_by_user_id": proposer["id"], "kind": kind, "status": status, "scheduled_at": T(when),
        "duration_minutes": minutes, "address_snapshot": prop["address"],
        "notes": rng.choice(["Llamar al llegar a portería.", "Traer escalera.", "Revisar también el baño social.", None]),
        "cancel_reason": "El cliente viaja esa semana." if status == "CANCELLED" else None,
        "outcome": outcome if status == "COMPLETED" else None,
        "reminder_sent_at": T(when - DAY) if status in ("COMPLETED", "NO_SHOW") or when < DAY else None,
        "completed_at": T(when + minutes * 60) if status == "COMPLETED" else None,
        "created_at": T(min(when - 2 * DAY, -HOUR)), "updated_at": T(min(when, -HOUR))})
    if kind == "KITCHEN_ASSESSMENT":
        for position, label in enumerate(KITCHEN):
            result = None
            if status == "COMPLETED":
                result = rng.choices(["OK", "NEEDS_WORK", "NOT_APPLICABLE"], weights=[60, 32, 8])[0]
            sql.add("visit_checklist_items", {"visit_id": visit_id, "position": position, "label": label,
                                              "result": result,
                                              "note": "Cambiar antes de entregar." if result == "NEEDS_WORK" else None})
    return visit_id


for request in requests_out:
    fixer = request.get("assigned")
    if fixer is None:
        continue
    if request.get("job_status") == "COMPLETED" and rng.random() < 0.35:
        when = business_time(request["completed_at"] - rng.randint(1, 3) * DAY)
        add_visit(request, fixer, rng.choice([request["reporter"], fixer]), "EXECUTION", "COMPLETED", when, 120,
                  "Se hizo el arreglo y se probó con el cliente presente.")
    elif request.get("job_status") in ("ASSIGNED", "SCHEDULED"):
        when = business_time(rng.randint(1, 10) * DAY)
        add_visit(request, fixer, fixer, "EXECUTION", rng.choice(["CONFIRMED", "PROPOSED"]), when, 90)
for request in [r for r in requests_out if r["status"] == "OPEN" and r["quoted"]][:45]:
    fixer = request["quoted"][0]
    kind = "KITCHEN_ASSESSMENT" if request["specialty"] in ("CARPENTRY", "MASONRY") and rng.random() < 0.5 else "DIAGNOSTIC"
    status = rng.choices(["PROPOSED", "CONFIRMED", "CANCELLED"], weights=[45, 45, 10])[0]
    add_visit(request, fixer, request["reporter"], kind, status, business_time(rng.randint(1, 12) * DAY),
              rng.choice([60, 90]))
    notify(fixer, "VISIT_PROPOSED", "Te propusieron una visita técnica",
           f"Visita para \"{request['title']}\".", "/visits", request["id"], "VISIT", request["created"] + 5 * HOUR)
# Completed kitchen assessments with their checklist answered.
for request in [r for r in requests_out if r["specialty"] == "CARPENTRY" and r.get("job_status") == "COMPLETED"][:8]:
    when = business_time(request["created"] + DAY)
    if when < -DAY:
        add_visit(request, request["assigned"], request["reporter"], "KITCHEN_ASSESSMENT", "COMPLETED", when, 90,
                  "La cocina necesita cambio de mesón y gabinetes bajos; la red de gas está en buen estado.")

# ---------------------------------------------------------------- portafolios
PIECES = {"PLUMBING": ["Cambio de red hidráulica de baño", "Instalación de grifería monomando", "Reparación de fuga en cocina"],
          "ELECTRICAL": ["Tablero eléctrico organizado", "Iluminación LED en sala", "Circuito independiente para estufa"],
          "PAINTING": ["Pintura completa de apartamento", "Tratamiento de humedad y pintura", "Fachada renovada"],
          "CARPENTRY": ["Cocina integral en madera", "Clóset a medida", "Gabinetes con herrajes nuevos"],
          "MASONRY": ["Enchape de piso", "Mesón en granito", "Impermeabilización de terraza"],
          "GENERAL": ["Mantenimiento general de casa", "Instalación de repisas", "Arreglos menores de entrega"]}
for fixer in verified[:34]:
    pieces = 6 if fixer is users["fixer"] else rng.randint(3, 6)
    published_at = fixer["created"] + 20 * DAY
    for position in range(1, pieces + 1):
        specialty = fixer["specialties"][(position - 1) % len(fixer["specialties"])]
        tag = specialty if ("obra", specialty) in images else "GENERAL"
        media_id = media(fixer, "FIXER_PORTFOLIO", "obra", tag, published_at - HOUR)
        sql.add("portfolio_pieces", {"id": uid("piece", media_id), "fixer_user_id": fixer["id"],
                                     "media_asset_id": media_id,
                                     "title": PIECES[specialty][(position - 1) % 3],
                                     "description": f"Trabajo de {SPEC_LABEL[specialty]} en {fixer['city']}.",
                                     "display_position": position, "visibility": "PUBLIC" if position < 6 else "HIDDEN",
                                     "created_at": T(published_at - HOUR), "updated_at": T(published_at)})
    sql.add("fixer_portfolios", {"fixer_user_id": fixer["id"], "status": "PUBLISHED", "published_at": T(published_at),
                                 "updated_at": T(published_at)})

# ---------------------------------------------------------------- reputación, verificación y retiros
for fixer in fixers:
    row = fixer["profile_row"]
    if fixer["ratings"]:
        row["rating_count"] = len(fixer["ratings"])
        row["rating_average"] = round(sum(fixer["ratings"]) / len(fixer["ratings"]), 2)
    sql.add("fixer_profiles", row)
for fixer in fixers:
    for doc in fixer.get("docs", []):
        sql.add("fixer_verification_documents", doc)
for fixer in verified:
    available = [e for e in fixer.get("earnings", []) if e["status"] == "AVAILABLE" and e["released"] < -10 * DAY]
    if len(available) >= 2 and rng.random() < 0.7:
        chosen = available[: max(1, len(available) * 2 // 3)]
        counters["payout"] += 1
        requested = max(e["released"] for e in chosen) + DAY
        sql.add("payouts", {"id": uid("payout", counters["payout"]), "fixer_user_id": fixer["id"],
                            "amount": sum(e["net"] for e in chosen), "earning_count": len(chosen),
                            "status": "REQUESTED", "requested_at": T(requested)})
        for earning in chosen:
            for row in sql.tables["fixer_earnings"]:
                if row["id"] == earning["id"]:
                    row.update({"status": "PAID_OUT", "paid_out_at": T(requested), "updated_at": T(requested)})
                    earning["status"] = "PAID_OUT"

# Admin notices: fixers waiting for review, SLA escalations.
for fixer in [f for f in fixers if f["status"] == "PENDING" and f["profile_row"]["submitted_at"] is not None]:
    for admin in admins:
        notify(admin, "FIXER_VERIFICATION_DECIDED", "Técnico pendiente de revisión",
               f"{fixer['name']} envió sus documentos de verificación.", "/administration/fixer-review",
               None, "FIXER", -rng.randint(1, 5) * DAY, read=False)
for request in [r for r in requests_out if r["status"] in ("SLA_WARNING", "SLA_BREACHED")]:
    kind = "REQUEST_SLA_BREACHED" if request["status"] == "SLA_BREACHED" else "REQUEST_SLA_WARNING"
    for recipient in [request["owner"]] + ([request["manager"]] if request["manager"] else []):
        notify(recipient, kind, "SLA incumplido" if kind.endswith("BREACHED") else "SLA por vencer",
               f"\"{request['title']}\" necesita atención.", f"/requests/{request['id']}", request["id"], "REQUEST",
               request["created"] + 40 * HOUR, read=False)
for contract in contracts:
    if contract["status"] == "ACTIVE" and 0 < contract["end"] <= 40:
        for recipient in (contract["property"]["owner"], contract["tenant"]):
            notify(recipient, "CONTRACT_EXPIRING_30D", "Contrato por vencer en 30 días",
                   "Revisa las condiciones y decide si lo renuevas.", f"/contracts/{contract['id']}",
                   contract["id"], "CONTRACT", -2 * DAY, read=False)
for row in notifications:
    sql.add("notifications", row)

# ---------------------------------------------------------------- salida
ORDER = ["users", "user_roles", "fixer_profiles", "fixer_specialties", "media_assets", "fixer_verification_documents",
         "fixer_portfolios", "portfolio_pieces", "properties", "property_photos", "rental_contracts",
         "repair_requests", "repair_request_photos", "quotations", "jobs", "job_status_log", "job_evidences",
         "fixer_earnings", "payouts", "owner_payments", "chat_messages", "service_ratings", "property_work_history",
         "remodeling_projects", "remodeling_requirements", "request_invitations", "technical_visits",
         "visit_checklist_items", "notifications"]
sql.order = [t for t in ORDER if t in sql.tables] + [t for t in sql.order if t not in ORDER]
with open(os.path.join(RESOURCES, "colombia.sql"), "w") as fh:
    fh.write(sql.render())
summary = {table: len(rows) for table, rows in sql.tables.items()}
json.dump(summary, open(os.path.join(RESOURCES, "resumen.json"), "w"), indent=1)
for table in sql.order:
    print(f"{table:32s} {summary[table]:6d}")
print("total filas", sum(summary.values()))
